package org.jellyfin.androidtv.ui.playback;

import static org.koin.java.KoinJavaComponent.get;
import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.media3.common.util.StuckPlayerException;
import androidx.media3.common.util.UnstableApi;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.auth.repository.SessionRepository;
import org.jellyfin.androidtv.data.compat.PlaybackException;
import org.jellyfin.androidtv.data.compat.StreamInfo;
import org.jellyfin.androidtv.data.compat.VideoOptions;
import org.jellyfin.androidtv.data.model.DataRefreshService;
import org.jellyfin.androidtv.data.syncplay.SyncPlayUtils;
import org.jellyfin.androidtv.preference.SystemPreferences;
import org.jellyfin.androidtv.preference.UserPreferences;
import org.jellyfin.androidtv.preference.UserSettingPreferences;
import org.jellyfin.androidtv.preference.constant.NextUpBehavior;
import org.jellyfin.androidtv.preference.constant.StillWatchingBehavior;
import org.jellyfin.androidtv.preference.constant.ZoomMode;
import org.jellyfin.androidtv.ui.InteractionTrackerViewModel;
import org.jellyfin.androidtv.ui.livetv.LiveTvStatusNotice;
import org.jellyfin.androidtv.ui.livetv.TvManager;
import org.jellyfin.androidtv.ui.playback.PrePlaybackTrackSelector;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.UUIDUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.ReportingHelper;
import org.jellyfin.androidtv.util.apiclient.Response;
import org.jellyfin.androidtv.util.profile.DeviceProfileKt;
import org.jellyfin.androidtv.util.sdk.ApiClientFactory;
import org.jellyfin.androidtv.util.sdk.compat.JavaCompat;
import org.jellyfin.sdk.api.client.ApiClient;
import org.jellyfin.sdk.model.ServerVersion;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.jellyfin.sdk.model.api.BaseItemKind;
import org.jellyfin.sdk.model.api.DeviceProfile;
import org.jellyfin.sdk.model.api.LocationType;
import org.jellyfin.sdk.model.api.MediaSourceInfo;
import org.jellyfin.sdk.model.api.MediaStream;
import org.jellyfin.sdk.model.api.MediaStreamType;
import org.jellyfin.sdk.model.api.PlayMethod;
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod;
import org.jellyfin.sdk.model.serializer.UUIDSerializerKt;
import org.koin.java.KoinJavaComponent;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import kotlin.Lazy;
import timber.log.Timber;

public class PlaybackController implements PlaybackControllerNotifiable {
    // Frequency to report playback progress
    private final static long PROGRESS_REPORTING_INTERVAL = TimeUtils.secondsToMillis(3);
    // Frequency to report paused state
    private static final long PROGRESS_REPORTING_PAUSE_INTERVAL = TimeUtils.secondsToMillis(15);

    private Lazy<PlaybackManager> playbackManager = inject(PlaybackManager.class);
    private Lazy<UserPreferences> userPreferences = inject(UserPreferences.class);
    private Lazy<VideoQueueManager> videoQueueManager = inject(VideoQueueManager.class);
    private Lazy<ApiClient> api = inject(ApiClient.class);
    private Lazy<ApiClientFactory> apiClientFactory = inject(ApiClientFactory.class);
    private Lazy<SessionRepository> sessionRepository = inject(SessionRepository.class);
    private Lazy<DataRefreshService> dataRefreshService = inject(DataRefreshService.class);
    private Lazy<ReportingHelper> reportingHelper = inject(ReportingHelper.class);
    private final Lazy<InteractionTrackerViewModel> lazyInteractionTracker = inject(InteractionTrackerViewModel.class);
    private Lazy<PrePlaybackTrackSelector> trackSelector = inject(PrePlaybackTrackSelector.class);
    private Lazy<SystemPreferences> systemPreferences = inject(SystemPreferences.class);
    private Lazy<org.jellyfin.androidtv.data.syncplay.SyncPlayManager> syncPlayManager = inject(org.jellyfin.androidtv.data.syncplay.SyncPlayManager.class);

    List<BaseItemDto> mItems;
    VideoManager mVideoManager;
    int mCurrentIndex;
    protected long mCurrentPosition = 0;
    private PlaybackState mPlaybackState = PlaybackState.IDLE;

    private StreamInfo mCurrentStreamInfo;

    private final InteractionTrackerViewModel interactionTracker;

    @Nullable
    private CustomPlaybackOverlayFragment mFragment;
    private Boolean spinnerOff = false;

    protected VideoOptions mCurrentOptions;
    private int mDefaultAudioIndex = -1;
    protected boolean burningSubs = false;
    private float mRequestedPlaybackSpeed = -1.0f;
    
    // Flag to prevent echoing SyncPlay commands back to the group
    private boolean isRespondingToSyncPlayCommand = false;

    private Runnable mReportLoop;
    private Handler mHandler;

    private long mStartPosition = 0;

    // tmp position used when seeking
    private long mSeekPosition = -1;
    private boolean wasSeeking = false;
    private boolean finishedInitialSeek = false;

    private LocalDateTime mCurrentProgramEnd = null;
    private LocalDateTime mCurrentProgramStart = null;
    private long mCurrentTranscodeStartTime;
    private boolean isLiveTv = false;
    // A recording still recording, played from its start (#239, GrowingRecording.kt)
    private boolean growingRecording = false;
    // Its stream ended after the recording finished: a normal stop, which marks it watched
    private boolean growingFinished = false;
    private int growingRestarts = 0;
    // Watch live on a recording still recording: start at its live end (GROWING_LIVE_EDGE_MS)
    private boolean growingStartAtLive = false;
    private final LiveTuneAttempts liveTunes = new LiveTuneAttempts();
    private boolean directStreamLiveTv;
    private int playbackRetries = 0;
    private long lastPlaybackError = 0;
    // Playback advanced since the last error: only then does a quiet spell reset the retries.
    private boolean progressSinceError = false;
    // Live TV: the in-place re-prepare was used in this burst of errors (#19).
    private boolean liveReprepareTried = false;
    // Live TV: when the stream last advanced, or when it was handed to the player (#60).
    private long liveProgressAt = 0;
    private boolean liveHasPlayed = false;
    // The item whose subtitle the user picked (details screen or player): only then is a
    // subtitle the server must draw onto a remote source's picture used (H1).
    @Nullable
    private UUID subtitlePickedFor;
    // The current stream advanced at least once; before that, a server stream may still be
    // preparing, and is waited for in place up to SERVER_START_WAITS times per item (H1).
    private boolean streamHasPlayed = false;
    private int serverStartWaits = 0;
    private Runnable mLiveStallWatch;

    public PlaybackController(List<BaseItemDto> items, CustomPlaybackOverlayFragment fragment) {
        this(items, fragment, 0);
    }

    public PlaybackController(List<BaseItemDto> items, CustomPlaybackOverlayFragment fragment, int startIndex) {
        mItems = items;
        mCurrentIndex = 0;
        if (items != null && startIndex > 0 && startIndex < items.size()) {
            mCurrentIndex = startIndex;
        }
        mFragment = fragment;
        mHandler = new Handler();

        interactionTracker = lazyInteractionTracker.getValue();

        // Register SyncPlay callback to handle incoming commands
        setupSyncPlayCallback();
    }
    
    private void setupSyncPlayCallback() {
        syncPlayManager.getValue().setPlaybackCallback(new org.jellyfin.androidtv.data.syncplay.SyncPlayManager.SyncPlayPlaybackCallback() {
            @Override
            public void onPlay(long positionMs) {
                Timber.i("SyncPlay onPlay callback: positionMs=%d, currentState=%s", positionMs, mPlaybackState);
                if (mPlaybackState == PlaybackState.PAUSED || mPlaybackState == PlaybackState.BUFFERING || mPlaybackState == PlaybackState.IDLE) {
                    isRespondingToSyncPlayCommand = true;
                    // If position is 0 and we're paused, resume from current position instead of seeking to start
                    if (positionMs <= 0 && mPlaybackState == PlaybackState.PAUSED && hasInitializedVideoManager()) {
                        Timber.i("SyncPlay: Resuming from current position");
                        mVideoManager.play();
                        mPlaybackState = PlaybackState.PLAYING;
                    } else {
                        play(positionMs);
                    }
                    isRespondingToSyncPlayCommand = false;
                } else {
                    Timber.w("SyncPlay onPlay ignored: state=%s", mPlaybackState);
                }
            }

            @Override
            public void onPause(long positionMs) {
                Timber.i("SyncPlay onPause callback: positionMs=%d, currentState=%s", positionMs, mPlaybackState);
                if (mPlaybackState == PlaybackState.PLAYING || mPlaybackState == PlaybackState.BUFFERING) {
                    isRespondingToSyncPlayCommand = true;
                    pause();
                    isRespondingToSyncPlayCommand = false;
                } else {
                    Timber.w("SyncPlay onPause ignored: state=%s", mPlaybackState);
                }
            }

            @Override
            public void onSeek(long positionMs) {
                isRespondingToSyncPlayCommand = true;
                seek(positionMs);
                isRespondingToSyncPlayCommand = false;
            }

            @Override
            public void onStop() {
                stop();
            }

            @Override
            public void onLoadQueue(java.util.List<java.util.UUID> itemIds, int startIndex, long startPositionTicks) {
                loadSyncPlayQueue(itemIds, startIndex, startPositionTicks);
            }
            
            @Override
            public long getCurrentPositionMs() {
                return getCurrentPosition();
            }
            
            @Override
            public boolean isPlaying() {
                return mPlaybackState == PlaybackState.PLAYING;
            }
            
            @Override
            public void setPlaybackSpeed(float speed) {
                PlaybackController.this.setPlaybackSpeed(speed);
            }
            
            @Override
            public float getPlaybackSpeed() {
                return PlaybackController.this.getPlaybackSpeed();
            }
        });
    }
    
    private void executeSyncPlayCommand(Runnable command) {
        if (isRespondingToSyncPlayCommand) return;
        new Thread(command).start();
    }
    
    private void loadSyncPlayQueue(final java.util.List<java.util.UUID> itemIds, final int startIndex, final long startPositionTicks) {
        if (itemIds.isEmpty()) return;
        
        if (mFragment == null) {
            Timber.i("Fragment is null, launching playback without lifecycle");
            
            org.jellyfin.androidtv.data.syncplay.SyncPlayQueueFetcher.fetchQueueAsync(
                itemIds,
                startIndex,
                startPositionTicks,
                new org.jellyfin.androidtv.data.syncplay.SyncPlayQueueHelper.QueueCallback() {
                    @Override
                    public void onQueueReady(java.util.List<org.jellyfin.sdk.model.api.BaseItemDto> items, int actualStartIndex, long startPositionMs) {
                        videoQueueManager.getValue().setCurrentVideoQueue(items, null);
                        videoQueueManager.getValue().setCurrentMediaPosition(actualStartIndex);
                        
                        android.content.Context context = KoinJavaComponent.<android.app.Application>get(android.app.Application.class);
                        Lazy<org.jellyfin.androidtv.ui.playback.PlaybackLauncher> playbackLauncher = inject(org.jellyfin.androidtv.ui.playback.PlaybackLauncher.class);
                        playbackLauncher.getValue().launch(context, items, (int) startPositionMs, false, actualStartIndex, false);
                    }

                    @Override
                    public void onError() {
                        Timber.e("Failed to load SyncPlay queue");
                    }
                }
            );
            return;
        }
        
        org.jellyfin.androidtv.data.syncplay.SyncPlayQueueHelper.fetchQueue(
            mFragment,
            itemIds,
            startIndex,
            startPositionTicks,
            new org.jellyfin.androidtv.data.syncplay.SyncPlayQueueHelper.QueueCallback() {
                @Override
                public void onQueueReady(java.util.List<org.jellyfin.sdk.model.api.BaseItemDto> items, int actualStartIndex, long startPositionMs) {
                    if (mFragment == null || mFragment.getActivity() == null) return;
                    
                    mItems = items;
                    mCurrentIndex = actualStartIndex;
                    mStartPosition = startPositionMs;
                    videoQueueManager.getValue().setCurrentVideoQueue(items, null);
                    videoQueueManager.getValue().setCurrentMediaPosition(actualStartIndex);
                    
                    // Play the first item to initialize playback
                    // The server will send commands to control playback state
                    org.jellyfin.sdk.model.api.BaseItemDto firstItem = items.get(actualStartIndex);
                    VideoOptions options = buildExoPlayerOptions(null, null, firstItem);
                    playInternal(firstItem, mStartPosition, options);
                }

                @Override
                public void onError() {
                    Timber.e("Failed to load SyncPlay queue");
                }
            }
        );
    }

    public boolean hasFragment() {
        return mFragment != null;
    }

    public CustomPlaybackOverlayFragment getFragment() {
        return mFragment;
    }

    public void init(@NonNull VideoManager mgr, @NonNull CustomPlaybackOverlayFragment fragment) {
        mVideoManager = mgr;
        mVideoManager.subscribe(this);
        mVideoManager.setZoom(userPreferences.getValue().get(UserPreferences.Companion.getPlayerZoomMode()));
        mFragment = fragment;
        directStreamLiveTv = userPreferences.getValue().get(UserPreferences.Companion.getLiveTvDirectPlayEnabled());
    }

    public void setItems(List<BaseItemDto> items) {
        mItems = items;
        mCurrentIndex = 0;
    }

    public float getPlaybackSpeed() {
        if (hasInitializedVideoManager()) {
            // Actually poll the video manager, since exoplayer can revert back
            // to 1x if it can't go faster, so we should check directly
            return mVideoManager.getPlaybackSpeed();
        } else {
            return mRequestedPlaybackSpeed;
        }
    }

    public void setPlaybackSpeed(float speed) {
        mRequestedPlaybackSpeed = speed;
        if (hasInitializedVideoManager()) {
            mVideoManager.setPlaybackSpeed(speed);
        }
    }

    public void setAudioDelay(long delayMs) {
        if (hasInitializedVideoManager()) {
            mVideoManager.setAudioDelay(delayMs);
        }
    }

    public BaseItemDto getCurrentlyPlayingItem() {
        return mItems.size() > mCurrentIndex ? mItems.get(mCurrentIndex) : null;
    }

    public boolean hasInitializedVideoManager() {
        return mVideoManager != null && mVideoManager.isInitialized();
    }

    @Nullable
    public VideoManager getVideoManager() {
        return mVideoManager;
    }

    public MediaSourceInfo getCurrentMediaSource() {
        if (mCurrentStreamInfo != null && mCurrentStreamInfo.getMediaSource() != null) {
            return mCurrentStreamInfo.getMediaSource();
        } else {
            BaseItemDto item = getCurrentlyPlayingItem();
            List<MediaSourceInfo> mediaSources = item.getMediaSources();

            if (mediaSources == null || mediaSources.isEmpty()) {
                return null;
            } else {
                // Prefer the media source with the same id as the item
                for (MediaSourceInfo mediaSource : mediaSources) {
                    UUID sourceId = UUIDSerializerKt.toUUIDOrNull(mediaSource.getId());
                    if (sourceId != null && sourceId.equals(item.getId())) {
                        return mediaSource;
                    }
                }
                // Or fallback to the first media source if none match
                return mediaSources.get(0);
            }
        }
    }

    public StreamInfo getCurrentStreamInfo() {
        return mCurrentStreamInfo;
    }

    public boolean canSeek() {
        return !isLiveTv || !directStreamLiveTv;
    }

    public boolean isGrowingRecording() {
        return growingRecording;
    }

    public boolean isLiveTv() {
        return isLiveTv;
    }

    public int getSubtitleStreamIndex() {
        return (mCurrentOptions != null && mCurrentOptions.getSubtitleStreamIndex() != null) ? mCurrentOptions.getSubtitleStreamIndex() : -1;
    }

    public boolean isTranscoding() {
        // use or here so that true is the default since
        // this method is used to exclude features that may break unless we are sure playback is direct
        return mCurrentStreamInfo == null || mCurrentStreamInfo.getPlayMethod() == PlayMethod.TRANSCODE;
    }

    /**
     * Check if subtitles are being burned/encoded into the video stream.
     * When true, subtitle delay cannot be applied because the subtitles
     * are part of the video frames, not separate text tracks.
     */
    public boolean isBurningSubtitles() {
        return burningSubs;
    }

    public boolean hasNextItem() {
        return mItems != null && mCurrentIndex < mItems.size() - 1;
    }

    public BaseItemDto getNextItem() {
        return hasNextItem() ? mItems.get(mCurrentIndex + 1) : null;
    }

    public boolean hasPreviousItem() {
        return mItems != null && mCurrentIndex - 1 >= 0;
    }

    public boolean isPlaying() {
        // since playbackController is so closely tied to videoManager, check if it is playing too since they can fall out of sync
        return mPlaybackState == PlaybackState.PLAYING && hasInitializedVideoManager() && mVideoManager.isPlaying();
    }

    @OptIn(markerClass = UnstableApi.class)
    public void playerErrorEncountered() {
        // Reset the retry count if playback ran again for more than 30 s since the previous error.
        // Time alone isn't enough: a live stream that never recovers fails about every 30 s
        // (the server's remux gives up), so it used to retry for ever (#19, #60).
        if (playbackRetries > 0 && progressSinceError && Instant.now().toEpochMilli() - lastPlaybackError > 30000) {
            Timber.i("playback stabilized - retry count reset to 0 from %s", playbackRetries);
            playbackRetries = 0;
        }

        playbackRetries++;
        lastPlaybackError = Instant.now().toEpochMilli();
        progressSinceError = false;

        // Detect the decoder-wedge failure mode: the player is READY with data flowing but makes
        // zero progress until media3's watchdog kills it. This is almost always a hardware decoder
        // that advertises support for a codec it can't actually decode (e.g. E-AC3 on some Sony
        // Bravias), and since audio is ExoPlayer's clock, a wedged audio decoder freezes playback
        // even though video decodes fine. Blocking the codec removes it from the device profile,
        // so the retry below gets a server-side audio transcode instead of re-feeding the same
        // broken decoder — which the normal retry ladder never fixes (it only degrades video).
        String stuckAudioCodec = null;
        androidx.media3.common.PlaybackException playerError =
                hasInitializedVideoManager() ? mVideoManager.getLastPlayerError() : null;
        if (playerError != null
                && playerError.getCause() instanceof StuckPlayerException
                && ((StuckPlayerException) playerError.getCause()).stuckType == StuckPlayerException.STUCK_PLAYING_NO_PROGRESS) {
            String codec = getCurrentAudioCodec();
            // Only a decoder that was fed and did nothing is to blame (#51): not live TV, a
            // transcode or a remote stream, whose input can simply stop, and never a codec Media3
            // decodes in software. Data "kept arriving" = the buffer reached past the frozen playhead.
            long buffered = hasInitializedVideoManager() ? mVideoManager.getBufferedPosition() : -1;
            long position = hasInitializedVideoManager() ? mVideoManager.getCurrentPosition() : -1;
            long bufferAhead = buffered >= 0 && position >= 0 ? buffered - position : 0;
            MediaSourceInfo stuckSource = getCurrentMediaSource();
            boolean remote = RemoteSubtitlePolicy.isRemoteSource(stuckSource);
            boolean blame = AudioCodecBlockPolicy.shouldBlockCodec(codec, isTranscoding(), remote, isLiveTv, bufferAhead);
            Timber.w("Stuck playback: audio %s, %s%s, %d ms buffered ahead - %s", codec,
                    isTranscoding() ? "transcode" : "direct", isLiveTv ? ", live" : (remote ? ", remote" : ""),
                    bufferAhead, blame ? "blaming the audio decoder" : "not a decoder fault");
            if (blame) {
                stuckAudioCodec = codec;
                if (blockAudioCodec(stuckAudioCodec) && mFragment != null) {
                    Utils.showToast(mFragment.getContext(),
                            mFragment.getString(R.string.audio_decoder_fallback, stuckAudioCodec.toUpperCase(Locale.ROOT)));
                }
            }
        }

        // Retry ladder (see buildExoPlayerOptions): attempt 1 disables direct play, attempt 2
        // disables direct stream AND forces an H.264 transcode. Allow up to 4 attempts so the
        // guaranteed-playable H.264 transcode actually gets tried (and retried once) before we
        // give up — previously the ceiling of 3 cut it off at its first attempt.
        if (playbackRetries < 4) {
            // Retry silently — the retry ladder recovers almost every time (direct
            // play -> direct stream -> H.264 transcode), so flashing a "playback
            // error" toast right before playback starts is just noise. The user
            // only needs to hear about it if all retries are exhausted below.
            Timber.i("Player error encountered - retrying (attempt %d)", playbackRetries);
            StreamInfo failedStream = mCurrentStreamInfo;
            BaseItemDto failedItem = getCurrentlyPlayingItem();
            stop();
            if (isLiveTv && mFragment != null) {
                // Open the channel again only once the server has handled the stop of this
                // attempt, or the new stream can be left open for good (#50).
                final long position = mCurrentPosition;
                startSpinner();
                reportingHelper.getValue().whenStopReported(mFragment, () -> {
                    if (mFragment != null && mPlaybackState == PlaybackState.IDLE) play(position);
                });
            } else if (mFragment != null && failedItem != null && failedStream != null
                    && failedStream.getPlayMethod() != PlayMethod.DIRECT_PLAY) {
                // Stop the failed attempt's encode on the server first, and ask for the next
                // stream only once that is answered: a stream still being prepared otherwise
                // started next to the retry's (H1).
                final long position = mCurrentPosition;
                reportingHelper.getValue().stopEncoding(mFragment, failedItem, failedStream);
                startSpinner();
                reportingHelper.getValue().whenStopReported(mFragment, () -> {
                    if (mFragment != null && mPlaybackState == PlaybackState.IDLE) play(position);
                });
            } else {
                play(mCurrentPosition);
            }
        } else {
            mPlaybackState = PlaybackState.ERROR;
            stopLiveStallWatch();
            if (mFragment != null) {
                String message = stuckAudioCodec != null
                        ? mFragment.getString(R.string.too_many_errors_audio, stuckAudioCodec.toUpperCase(Locale.ROOT))
                        : isLiveTv ? mFragment.getString(R.string.msg_live_channel_unavailable)
                        : mFragment.getString(R.string.too_many_errors);
                Utils.showToast(mFragment.getContext(), message);
                mFragment.closePlayer();
            }
        }
    }

    /**
     * Codec of the audio stream the player was fed when the error occurred. Reports the source
     * stream's codec — correct for direct play and remux, and still the right codec to blame on a
     * transcode that copies the audio stream (the server only re-encodes audio the profile
     * disallows).
     */
    @Nullable
    private String getCurrentAudioCodec() {
        MediaSourceInfo mediaSource = getCurrentMediaSource();
        if (mediaSource == null || mediaSource.getMediaStreams() == null) return null;
        Integer index = mCurrentOptions != null ? mCurrentOptions.getAudioStreamIndex() : null;
        if (index == null) index = mediaSource.getDefaultAudioStreamIndex();
        MediaStream firstAudioStream = null;
        for (MediaStream stream : mediaSource.getMediaStreams()) {
            if (stream.getType() != MediaStreamType.AUDIO) continue;
            if (firstAudioStream == null) firstAudioStream = stream;
            if (index != null && index.equals(stream.getIndex())) return stream.getCodec();
        }
        return firstAudioStream != null ? firstAudioStream.getCodec() : null;
    }

    private Set<String> getBlockedAudioCodecs() {
        return systemPreferences.getValue().getBlockedAudioCodecs();
    }

    /**
     * Persistently mark an audio codec as broken on this device so the device profile stops
     * advertising it. Returns false if it was already blocked (meaning the codec block did not
     * fix the stall, so the failure lies elsewhere).
     */
    private boolean blockAudioCodec(@NonNull String codec) {
        if (!systemPreferences.getValue().blockAudioCodec(codec)) return false;
        Timber.w("Audio codec %s marked broken on this device after stuck playback - the server will transcode it from now on", codec.toLowerCase(Locale.ROOT));
        return true;
    }

    // central place to update mCurrentPosition
    private void refreshCurrentPosition() {
        long newPos = -1;

        if (isLiveTv && mCurrentProgramStart != null) {
            newPos = getRealTimeProgress();
            // live tv
        } else if (hasInitializedVideoManager()) {
            if (currentSkipPos != 0 || (!isPlaying() && mSeekPosition != -1)) {
                newPos = mSeekPosition;
                // use seekPosition until playback starts
            } else if (isPlaying()) {
                if (finishedInitialSeek) {
                    // playback has started following initial seek for direct play and hls
                    // get current position and reset seekPosition
                    newPos = mVideoManager.getCurrentPosition();
                    mSeekPosition = -1;
                } else if (wasSeeking) {
                    // the initial seek for direct play and hls completed
                    finishedInitialSeek = true;
                } else if (mSeekPosition != -1) {
                    // the initial seek for direct play and hls hasn't happened yet
                    newPos = mSeekPosition;
                }
                wasSeeking = false;
            }
        }
        // use original value if new one isn't available
        mCurrentPosition = newPos != -1 ? newPos : mCurrentPosition;
    }

    public void play(long position) {
        play(position, null);
    }

    protected void play(long position, @Nullable Integer forcedSubtitleIndex) {
        String forcedAudioLanguage = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
        growingStartAtLive = GrowingRecordingKt.isLiveEdgeStart(position);
        if (growingStartAtLive) position = 0;
        Timber.i("Play called from state: %s with pos: %d, sub index: %d and forced audio: %s", mPlaybackState, position, forcedSubtitleIndex, forcedAudioLanguage);

        if (mFragment == null) {
            Timber.w("mFragment is null, returning");
            return;
        }

        if (position < 0) {
            Timber.i("Negative start requested - adjusting to zero");
            position = 0;
        }

        switch (mPlaybackState) {
            case PLAYING:
                // do nothing
                break;
            case SEEKING:
                if (!hasInitializedVideoManager()) {
                    return;
                }
                mVideoManager.play();
                mPlaybackState = PlaybackState.PLAYING;
                if (mFragment != null) mFragment.setFadingEnabled(true);
                startReportLoop();
                break;
            case PAUSED:
                if (!hasInitializedVideoManager()) {
                    return;
                }
                // Apply unpause rewind if configured
                UserSettingPreferences prefs = KoinJavaComponent.<UserSettingPreferences>get(UserSettingPreferences.class);
                int unpauseRewindMs = prefs.get(UserSettingPreferences.Companion.getUnpauseRewindDuration());
                if (unpauseRewindMs > 0) {
                    long rewindPosition = Math.max(0, getCurrentPosition() - unpauseRewindMs);
                    if (isTranscoding()) {
                        // For transcoded content, only seek if within buffer to avoid server restart
                        Timber.i("Attempting buffer seek for transcoded content, rewind %d ms", unpauseRewindMs);
                        mVideoManager.seekWithinBuffer(rewindPosition);
                    } else {
                        // For direct play, always seek
                        Timber.i("Rewinding %d ms on unpause, seeking to: %d", unpauseRewindMs, rewindPosition);
                        mVideoManager.seekTo(rewindPosition);
                    }
                }
                // resume playback
                mVideoManager.play();
                mPlaybackState = PlaybackState.PLAYING; // won't get another onPrepared call
                mFragment.setFadingEnabled(true);
                startReportLoop();
                break;
            case BUFFERING:
                // onPrepared should take care of it
                break;
            case IDLE:
                // start new playback

                // set mSeekPosition so the seekbar will not default to 0:00
                mSeekPosition = position;
                mCurrentPosition = 0;

                mFragment.setFadingEnabled(false);

                BaseItemDto item = getCurrentlyPlayingItem();

                if (item == null) {
                    Timber.w("item is null - aborting play");
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_cannot_play));
                    mFragment.closePlayer();
                    return;
                }

                // make sure item isn't missing
                if (item.getLocationType() == LocationType.VIRTUAL) {
                    if (hasNextItem()) {
                        new AlertDialog.Builder(mFragment.getContext())
                                .setTitle(R.string.episode_missing)
                                .setMessage(R.string.episode_missing_message)
                                .setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        next();
                                    }
                                })
                                .setNegativeButton(R.string.lbl_no, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mFragment.closePlayer();
                                    }
                                })
                                .create()
                                .show();
                    } else {
                        new AlertDialog.Builder(mFragment.getContext())
                                .setTitle(R.string.episode_missing)
                                .setMessage(R.string.episode_missing_message_2)
                                .setPositiveButton(R.string.lbl_ok, new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(DialogInterface dialog, int which) {
                                        mFragment.closePlayer();
                                    }
                                })
                                .create()
                                .show();
                    }
                    return;
                }

                isLiveTv = item.getType() == BaseItemKind.TV_CHANNEL;
                startSpinner();

                // undo setting mSeekPosition for liveTV
                if (isLiveTv) mSeekPosition = -1;

                VideoOptions internalOptions = buildExoPlayerOptions(forcedSubtitleIndex, forcedAudioLanguage, item);

                playInternal(getCurrentlyPlayingItem(), position, internalOptions);
                mPlaybackState = PlaybackState.BUFFERING;
                mFragment.setPlayPauseActionState(0);
                mFragment.setCurrentTime(position);

                long duration = getCurrentlyPlayingItem().getRunTimeTicks() != null ? getCurrentlyPlayingItem().getRunTimeTicks() / 10000 : -1;
                if (mVideoManager != null)
                    mVideoManager.setMetaDuration(duration);

                break;
        }
    }

    @NonNull
    private VideoOptions buildExoPlayerOptions(
            @Nullable Integer forcedSubtitleIndex,
            @Nullable String forcedAudioLanguage,
            BaseItemDto item) {
        VideoOptions internalOptions = new VideoOptions();
        internalOptions.setItemId(item.getId());
        internalOptions.setMediaSources(item.getMediaSources());
        
        // Extract serverId for multi-server support
        // Only set serverId when the item is from a different server than the current session.
        // This avoids creating a separate API client (with a different device ID) for same-server
        // items, which can cause transcoding session mismatches on the server.
        UUID parsedServerId = UUIDUtils.parseUUID(item.getServerId());
        UUID currentServerId = sessionRepository.getValue().getCurrentSession().getValue() != null ?
            sessionRepository.getValue().getCurrentSession().getValue().getServerId() : null;
        if (parsedServerId != null && !parsedServerId.equals(currentServerId)) {
            internalOptions.setServerId(parsedServerId);
            Timber.i("PlaybackController: Using cross-server API for playback: item server %s, current server %s", parsedServerId, currentServerId);
        }
        
        if (playbackRetries > 0 || (isLiveTv && !directStreamLiveTv)) internalOptions.setEnableDirectPlay(false);
        if (playbackRetries > 1) internalOptions.setEnableDirectStream(false);
        
        // Check for pre-selected tracks from the details screen
        String itemIdString = UUIDSerializerKt.toUUIDOrNull(item.getId().toString()).toString();
        Integer preSelectedAudio = trackSelector.getValue().getSelectedAudioTrack(itemIdString);
        Integer preSelectedSubtitle = trackSelector.getValue().getSelectedSubtitleTrack(itemIdString);
        
        if (mCurrentOptions != null) {
            internalOptions.setSubtitleStreamIndex(mCurrentOptions.getSubtitleStreamIndex());
            internalOptions.setAudioStreamIndex(mCurrentOptions.getAudioStreamIndex());
        }
        
        if (preSelectedAudio != null && preSelectedAudio >= 0) {
            Timber.i("Applying pre-selected audio track: %d", preSelectedAudio);
            internalOptions.setAudioStreamIndex(preSelectedAudio);
        } else if (forcedAudioLanguage != null) {
            MediaSourceInfo currentMediaSource = getCurrentMediaSource();
            if (currentMediaSource != null && currentMediaSource.getMediaStreams() != null) {
                for (MediaStream stream : currentMediaSource.getMediaStreams()) {
                    if (stream.getType() == MediaStreamType.AUDIO && forcedAudioLanguage.equals(stream.getLanguage())) {
                        internalOptions.setAudioStreamIndex(stream.getIndex());
                        break;
                    }
                }
            }
        }
        
        if (preSelectedSubtitle != null) {
            Timber.i("Applying pre-selected subtitle track: %d", preSelectedSubtitle);
            internalOptions.setSubtitleStreamIndex(preSelectedSubtitle);
        } else if (forcedSubtitleIndex != null) {
            internalOptions.setSubtitleStreamIndex(forcedSubtitleIndex);
        }
        if (preSelectedSubtitle != null || forcedSubtitleIndex != null) subtitlePickedFor = item.getId();
        
        String preSelectedMediaSource = trackSelector.getValue().getSelectedMediaSource(itemIdString);
        if (preSelectedMediaSource != null) {
            Timber.i("Applying pre-selected media source: %s", preSelectedMediaSource);
            internalOptions.setMediaSourceId(preSelectedMediaSource);
        }

        if (preSelectedAudio != null || preSelectedSubtitle != null || preSelectedMediaSource != null) {
            Timber.i("Clearing track pre-selections for next playback");
            trackSelector.getValue().clearSelections();
        }
        
        if (internalOptions.getMediaSourceId() == null) {
            MediaSourceInfo currentMediaSource = getCurrentMediaSource();
            if (!isLiveTv && currentMediaSource != null) {
                // Not while it records: Jellyfin then offers another source (#239)
                internalOptions.setMediaSourceId(GrowingRecordingKt.playbackMediaSourceId(item, currentMediaSource.getId()));
            }
        }
        // Once direct play AND direct stream have both been disabled by earlier retries (i.e. we
        // are now forcing a full server transcode), also force the transcode target to H.264.
        // Otherwise a device that over-reports HEVC support (emulators, some boxes) receives an
        // HEVC transcode it still can't decode, so the fallback fails exactly like direct play did.
        boolean forceH264Transcode = playbackRetries > 1;
        DeviceProfile internalProfile = DeviceProfileKt.createDeviceProfile(
                mFragment.getContext(),
                userPreferences.getValue(),
                get(ServerVersion.class),
                forceH264Transcode,
                getBlockedAudioCodecs()
        );
        internalOptions.setProfile(internalProfile);
        return internalOptions;
    }

    private void playInternal(final BaseItemDto item, final Long position, final VideoOptions internalOptions) {
        if (isLiveTv) {
            updateTvProgramInfo();
            TvManager.setLastLiveTvChannel(item.getId());
            //internal/exo player
            Timber.i("Using internal player for Live TV");
            final int tune = liveTunes.start();
            playbackManager.getValue().getVideoStreamInfo(mFragment, internalOptions, position * 10000, new Response<StreamInfo>(mFragment.getLifecycle()) {
                @Override
                public void onResponse(StreamInfo response) {
                    if (!isActive()) return;
                    if (mVideoManager == null)
                        return;
                    if (!liveTunes.isCurrent(tune)) {
                        // CH+ again while this one opened: the newer channel plays, not this one (#176)
                        Timber.i("Dropping the answer of an older tune");
                        return;
                    }
                    mCurrentOptions = internalOptions;
                    startItem(item, position, response);
                }

                @Override
                public void onError(Exception exception) {
                    if (!isActive()) return;
                    if (!liveTunes.isCurrent(tune)) return;
                    handlePlaybackInfoError(exception);
                }
            });
        } else {
            // A recording still recording is asked from 0:00 and the player seeks itself, so the
            // part before the position stays in the stream (#239)
            long startTicks = GrowingRecordingKt.isRecordingInProgress(item) ? 0 : position * 10000;
            playbackManager.getValue().getVideoStreamInfo(mFragment, internalOptions, startTicks, new Response<StreamInfo>(mFragment.getLifecycle()) {
                @Override
                public void onResponse(StreamInfo internalResponse) {
                    if (!isActive()) return;
                    Timber.i("Internal player would %s", internalResponse.getPlayMethod().equals(PlayMethod.TRANSCODE) ? "transcode" : "direct stream");
                    if (mVideoManager == null)
                        return;
                    boolean userPicked = item.getId().equals(subtitlePickedFor);
                    if (RemoteSubtitlePolicy.skipAutoBurnIn(userPicked, internalResponse.getMediaSource(), internalOptions.getSubtitleStreamIndex())) {
                        // Ask again without it; the user can still pick it in the player (H1).
                        Timber.i("Remote source: the default subtitle needs burn-in, starting without subtitles");
                        internalOptions.setSubtitleStreamIndex(-1);
                        playInternal(item, position, internalOptions);
                        return;
                    }
                    mCurrentOptions = internalOptions;
                    if (internalOptions.getSubtitleStreamIndex() == null) burningSubs = internalResponse.getSubtitleDeliveryMethod() == SubtitleDeliveryMethod.ENCODE;
                    startItem(item, position, internalResponse);
                }

                @Override
                public void onError(Exception exception) {
                    if (!isActive()) return;
                    Timber.e(exception, "Unable to get stream info for internal player");
                    if (mVideoManager == null)
                        return;
                }
            });
        }
    }

    private void handlePlaybackInfoError(Exception exception) {
        Timber.e(exception, "Error getting playback stream info");
        if (mFragment == null) return;
        if (exception instanceof PlaybackException) {
            PlaybackException ex = (PlaybackException) exception;
            switch (ex.getErrorCode()) {
                case NOT_ALLOWED:
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_playback_not_allowed));
                    break;
                case NO_COMPATIBLE_STREAM:
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_playback_incompatible));
                    break;
                case RATE_LIMIT_EXCEEDED:
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_playback_restricted));
                    break;
            }
        } else {
            // A live channel the provider can't serve right now fails here (the tuner didn't open)
            Utils.showToast(mFragment.getContext(), mFragment.getString(isLiveTv ? R.string.msg_live_channel_unavailable : R.string.msg_cannot_play));
        }
        if (mFragment != null) mFragment.closePlayer();
    }

    private void startItem(BaseItemDto item, long position, StreamInfo response) {
        if (!hasInitializedVideoManager() || !hasFragment()) {
            Timber.w("Error - attempting to play without:%s%s", hasInitializedVideoManager() ? "" : " [videoManager]", hasFragment() ? "" : " [overlay fragment]");
            return;
        }

        Integer preSelectedAudioIndex = mCurrentOptions.getAudioStreamIndex();
        mCurrentOptions.setAudioStreamIndex(null);

        mStartPosition = position;
        mCurrentStreamInfo = response;
        streamHasPlayed = false;
        growingRecording = GrowingRecordingKt.isGrowingRecordingSource(item.getType(), response.getMediaSource());
        growingFinished = false;
        if (growingRecording) {
            Timber.i("Playing a recording still recording from its start, then %d ms", position);
            // Its length is what the player has of it, never a runtime probed from the half-written file
            if (mVideoManager != null) mVideoManager.setMetaDuration(-1);
        }
        mCurrentOptions.setMediaSourceId(response.getMediaSource().getId());

        if (response.getMediaUrl() == null) {
            // If baking subtitles doesn't work (e.g. no permissions to transcode), disable them
            if (response.getSubtitleDeliveryMethod() == SubtitleDeliveryMethod.ENCODE && (response.getMediaSource().getDefaultSubtitleStreamIndex() == null || response.getMediaSource().getDefaultSubtitleStreamIndex() != -1)) {
                burningSubs = false;
                stop();
                play(position, -1);
            } else {
                handlePlaybackInfoError(null);
            }
            return;
        }

        // get subtitle info
        Integer preSelectedSubtitleIndex = mCurrentOptions.getSubtitleStreamIndex();
        boolean subtitlesDefaultToNone = userPreferences.getValue().get(UserPreferences.Companion.getSubtitlesDefaultToNone());
        if (preSelectedSubtitleIndex != null) {
            mCurrentOptions.setSubtitleStreamIndex(preSelectedSubtitleIndex);
            Timber.i("Using pre-selected subtitle index: %s", preSelectedSubtitleIndex);
        } else if (subtitlesDefaultToNone) {
            mCurrentOptions.setSubtitleStreamIndex(-1);
            Timber.i("default sub index set to -1 (None) - server default was %s", response.getMediaSource().getDefaultSubtitleStreamIndex());
        } else {
            mCurrentOptions.setSubtitleStreamIndex(response.getMediaSource().getDefaultSubtitleStreamIndex() != null ? response.getMediaSource().getDefaultSubtitleStreamIndex() : null);
            Timber.i("default sub index set to %s remote default %s", mCurrentOptions.getSubtitleStreamIndex(), response.getMediaSource().getDefaultSubtitleStreamIndex());
        }
        setDefaultAudioIndex(response);
        if (preSelectedAudioIndex != null) {
            mCurrentOptions.setAudioStreamIndex(preSelectedAudioIndex);
            Timber.i("Restoring pre-selected audio index: %s (default was %s)", preSelectedAudioIndex, mDefaultAudioIndex);
        }
        Timber.i("default audio index set to %s remote default %s", mDefaultAudioIndex, response.getMediaSource().getDefaultAudioStreamIndex());

        Long mbPos = position * 10000;

        // Refresh rate: the display mode that fits the video (DisplayModeSwitch.kt)
        boolean switchesDisplayMode = mFragment.getActivity() != null && DisplayModeSwitch.switchDisplayMode(
                mFragment.getActivity(), JavaCompat.getVideoStream(response.getMediaSource()),
                userPreferences.getValue().get(UserPreferences.Companion.getRefreshRateSwitchingBehavior()), isLiveTv);

        // set playback speed to user selection, or 1 if we're watching live-tv
        if (mVideoManager != null)
            mVideoManager.setPlaybackSpeed(isLiveTv() ? 1.0f : mRequestedPlaybackSpeed);

        if (mFragment != null) mFragment.updateDisplay();

        if (mVideoManager != null) {
            // Get server-specific API client for subtitle URLs with current user context
            ApiClient subtitleApi = api.getValue();
            if (mCurrentOptions.getServerId() != null) {
                UUID currentUserId = sessionRepository.getValue().getCurrentSession().getValue() != null ? 
                    sessionRepository.getValue().getCurrentSession().getValue().getUserId() : null;
                ApiClient serverApi = currentUserId != null ?
                    apiClientFactory.getValue().getApiClient(mCurrentOptions.getServerId(), currentUserId) :
                    apiClientFactory.getValue().getApiClientForServer(mCurrentOptions.getServerId());
                if (serverApi != null) {
                    subtitleApi = serverApi;
                    Timber.d("PlaybackController: Using server-specific API for subtitles: %s user: %s", mCurrentOptions.getServerId(), currentUserId);
                }
            }
            // From its start, or (Watch live) at the live end the player picks by default
            mVideoManager.setMediaStreamInfo(subtitleApi, response, growingRecording && !growingStartAtLive);
        }
        if (isLiveTv) startLiveStallWatch();

        PlaybackControllerHelperKt.applyMediaSegments(this, item, () -> {
            // The player may have closed while the segments loaded (Back right after tuning)
            if (mVideoManager == null) return null;
            // Set video start delay, and wait for the TV after a display mode switch
            long videoStartDelay = DisplayModeSwitch.videoStartDelayMs(
                    userPreferences.getValue().get(UserPreferences.Companion.getVideoStartDelay()), switchesDisplayMode);
            if (videoStartDelay > 0) {
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mVideoManager != null) {
                            mVideoManager.start();
                        }
                    }
                }, videoStartDelay);
            } else {
                mVideoManager.start();
            }

            dataRefreshService.getValue().setLastPlayedItem(item);
            reportingHelper.getValue().reportStart(mFragment, PlaybackController.this, item, response, mbPos, false);

            return null;
        });
    }

    public void startSpinner() {
        spinnerOff = false;
    }

    public void stopSpinner() {
        spinnerOff = true;
    }

    public int getAudioStreamIndex() {
        int currIndex = -1;

        // Use stream index from mCurrentOptions if it's set.
        // This should be null until the player has been queried at least once after playback starts
        //
        // Use DefaultAudioStreamIndex for transcoding since they are encoded with only one stream
        //
        // Otherwise, query the players
        if (mCurrentOptions.getAudioStreamIndex() != null) {
            currIndex = mCurrentOptions.getAudioStreamIndex();
        } else if (isTranscoding() && getCurrentMediaSource() != null && getCurrentMediaSource().getDefaultAudioStreamIndex() != null) {
            currIndex = getCurrentMediaSource().getDefaultAudioStreamIndex();
        } else if (hasInitializedVideoManager() && !isTranscoding()) {
            currIndex = mVideoManager.getExoPlayerTrack(MediaStreamType.AUDIO, getCurrentlyPlayingItem().getMediaStreams());
        }
        return currIndex;
    }

    private Integer bestGuessAudioTrack(MediaSourceInfo info) {
        if (info == null)
            return null;

        boolean videoFound = false;
        for (MediaStream track : info.getMediaStreams()) {
            if (track.getType() == MediaStreamType.VIDEO) {
                videoFound = true;
            } else {
                if (videoFound && track.getType() == MediaStreamType.AUDIO)
                    return track.getIndex();
            }
        }
        return null;
    }

    private Integer lastChosenLanguageAudioTrack(MediaSourceInfo info) {
        if (info == null)
            return null;

        boolean videoFound = false;
        for (MediaStream track : info.getMediaStreams()) {
            if (track.getType() == MediaStreamType.VIDEO) {
                videoFound = true;
            } else {
                if (videoFound
                    && track.getType() == MediaStreamType.AUDIO
                    && (track.getLanguage() != null && track.getLanguage().equals(videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode()))
                )
                    return track.getIndex();
            }
        }
        return null;
    }

    private void setDefaultAudioIndex(StreamInfo info) {
        if (mDefaultAudioIndex != -1)
            return;

        Integer lastChosenLanguage = lastChosenLanguageAudioTrack(info.getMediaSource());
        Integer remoteDefault = info.getMediaSource().getDefaultAudioStreamIndex();
        Integer bestGuess = bestGuessAudioTrack(info.getMediaSource());

        if (lastChosenLanguage != null)
            mDefaultAudioIndex = lastChosenLanguage;
        else if (remoteDefault != null)
            mDefaultAudioIndex = remoteDefault;
        else if (bestGuess != null)
            mDefaultAudioIndex = bestGuess;
        Timber.d("default audio index set to %s", mDefaultAudioIndex);
    }

    public void switchAudioStream(int index) {
        if (!(isPlaying() || isPaused()) || index < 0)
            return;

        MediaSourceInfo currentMediaSource = getCurrentMediaSource();
        if (currentMediaSource == null
                || currentMediaSource.getMediaStreams() == null
                || index >= currentMediaSource.getMediaStreams().size()) {
            return;
        }

        String lastAudioIsoCode = videoQueueManager.getValue().getLastPlayedAudioLanguageIsoCode();
        String currentAudioIsoCode = currentMediaSource.getMediaStreams().get(index).getLanguage();

        if (currentAudioIsoCode != null
                && (lastAudioIsoCode == null || !lastAudioIsoCode.equals(currentAudioIsoCode))) {
            videoQueueManager.getValue().setLastPlayedAudioLanguageIsoCode(
                    currentAudioIsoCode
            );
        }

        int currAudioIndex = getAudioStreamIndex();
        Timber.i("trying to switch audio stream from %s to %s", currAudioIndex, index);
        if (currAudioIndex == index) {
            Timber.d("skipping setting audio stream, already set to requested index %s", index);
            if (mCurrentOptions.getAudioStreamIndex() == null || mCurrentOptions.getAudioStreamIndex() != index) {
                Timber.i("setting mCurrentOptions audio stream index from %s to %s", mCurrentOptions.getAudioStreamIndex(), index);
                mCurrentOptions.setAudioStreamIndex(index);
            }
            return;
        }

        // get current timestamp first
        refreshCurrentPosition();

        if (!isTranscoding() && mVideoManager.setExoPlayerTrack(index, MediaStreamType.AUDIO, currentMediaSource.getMediaStreams())) {
            mCurrentOptions.setMediaSourceId(currentMediaSource.getId());
            mCurrentOptions.setAudioStreamIndex(index);
        } else {
            startSpinner();
            mCurrentOptions.setMediaSourceId(currentMediaSource.getId());
            mCurrentOptions.setAudioStreamIndex(index);
            stop();
            playInternal(getCurrentlyPlayingItem(), mCurrentPosition, mCurrentOptions);
            mPlaybackState = PlaybackState.BUFFERING;
        }
    }

    public void pause() {
        Timber.i("pause called at %s", mCurrentPosition);
        // if playback is paused and the seekbar is scrubbed, it will call pause even if already paused
        if (mPlaybackState == PlaybackState.PAUSED) {
            Timber.d("already paused, ignoring");
            return;
        }
        mPlaybackState = PlaybackState.PAUSED;
        if (hasInitializedVideoManager()) mVideoManager.pause();
        if (mFragment != null) {
            mFragment.setFadingEnabled(false);
            mFragment.setPlayPauseActionState(0);
        }

        stopReportLoop();
        startPauseReportLoop();
        
        org.jellyfin.androidtv.data.syncplay.SyncPlayManager manager = syncPlayManager.getValue();
        org.jellyfin.androidtv.data.syncplay.SyncPlayState state = manager.getState().getValue();
        if (state.getGroupInfo() != null && state.getGroupState() == org.jellyfin.sdk.model.api.GroupStateType.PLAYING) {
            executeSyncPlayCommand(() -> {
                try {
                    kotlinx.coroutines.BuildersKt.runBlocking(
                        kotlinx.coroutines.Dispatchers.getIO(),
                        (scope, continuation) -> manager.requestPause(continuation)
                    );
                } catch (Exception e) {
                    Timber.e(e, "Failed to sync pause with SyncPlay");
                }
            });
        }
    }

    public void playPause() {
        switch (mPlaybackState) {
            case PLAYING:
                pause();
                break;
            case PAUSED:
            case IDLE:
                stopReportLoop();
                play(getCurrentPosition());
                
                org.jellyfin.androidtv.data.syncplay.SyncPlayManager manager = syncPlayManager.getValue();
                org.jellyfin.androidtv.data.syncplay.SyncPlayState state = manager.getState().getValue();
                if (state.getGroupInfo() != null && state.getGroupState() == org.jellyfin.sdk.model.api.GroupStateType.PAUSED) {
                    executeSyncPlayCommand(() -> {
                        try {
                            kotlinx.coroutines.BuildersKt.runBlocking(
                                kotlinx.coroutines.Dispatchers.getIO(),
                                (scope, continuation) -> manager.requestPlay(continuation)
                            );
                        } catch (Exception e) {
                            Timber.e(e, "Failed to sync play with SyncPlay");
                        }
                    });
                }
                break;
        }
    }

    public void stop() {
        refreshCurrentPosition();
        Timber.i("stop called at %s", mCurrentPosition);
        stopReportLoop();
        stopLiveStallWatch();
        if (mPlaybackState != PlaybackState.IDLE && mPlaybackState != PlaybackState.UNDEFINED) {
            mPlaybackState = PlaybackState.IDLE;

            if (mVideoManager != null && mVideoManager.isPlaying()) mVideoManager.stopPlayback();
            if (getCurrentlyPlayingItem() != null && mCurrentStreamInfo != null) {
                Long mbPos = mCurrentPosition * 10000;
                reportingHelper.getValue().reportStopped(mFragment, getCurrentlyPlayingItem(), mCurrentStreamInfo, mbPos, growingRecording && !growingFinished);
            }
            clearPlaybackSessionOptions();
        }
    }

    public void refreshStream() {
        // get current timestamp first
        refreshCurrentPosition();

        stop();
        play(mCurrentPosition);
    }

    public void endPlayback(Boolean closeActivity) {
        if (closeActivity && mFragment != null) {
            mFragment.closePlayer();
        }
        stop();
        if (mVideoManager != null)
            mVideoManager.destroy();
        mFragment = null;
        mVideoManager = null;
        resetPlayerErrors();
    }

    public void endPlayback() {
        endPlayback(false);
    }
    
    public void onResume() {
        syncPlayManager.getValue().onAppResume();
    }
    
    public void onPause() {
        syncPlayManager.getValue().onAppPause();
    }

    private void resetPlayerErrors() {
        playbackRetries = 0;
        progressSinceError = false;
        liveReprepareTried = false;
        serverStartWaits = 0;
    }

    /**
     * Live TV only: a stream that stops advancing without an error or an end (the server's
     * playlist ended on an empty segment) left a black screen for good. If it doesn't advance
     * for LIVE_STALL_MS once it has played, or LIVE_START_STALL_MS before the first frame, it
     * goes through the normal retry path (#60).
     */
    private void startLiveStallWatch() {
        stopLiveStallWatch();
        liveProgressAt = Instant.now().toEpochMilli();
        liveHasPlayed = false;
        mLiveStallWatch = new Runnable() {
            @Override
            public void run() {
                if (!isLiveTv || mVideoManager == null) return;
                if (mPlaybackState == PlaybackState.PLAYING || mPlaybackState == PlaybackState.BUFFERING) {
                    long now = Instant.now().toEpochMilli();
                    if (mVideoManager.isPlaying()) {
                        liveProgressAt = now;
                        liveHasPlayed = true;
                    } else {
                        long limit = liveHasPlayed ? PlayerErrorPolicy.LIVE_STALL_MS : PlayerErrorPolicy.LIVE_START_STALL_MS;
                        if (now - liveProgressAt > limit) {
                            Timber.w("Live stream made no progress for %d s - retrying", (now - liveProgressAt) / 1000);
                            mLiveStallWatch = null;
                            playerErrorEncountered();
                            return;
                        }
                    }
                } else {
                    // paused, seeking or stopped: not a stall
                    liveProgressAt = Instant.now().toEpochMilli();
                }
                mHandler.postDelayed(this, 5000);
            }
        };
        mHandler.postDelayed(mLiveStallWatch, 5000);
    }

    private void stopLiveStallWatch() {
        if (mHandler != null && mLiveStallWatch != null) mHandler.removeCallbacks(mLiveStallWatch);
        mLiveStallWatch = null;
    }

    private void clearPlaybackSessionOptions() {
        mDefaultAudioIndex = -1;
        mSeekPosition = -1;
        finishedInitialSeek = false;
        wasSeeking = false;
        burningSubs = false;
        mCurrentStreamInfo = null;
    }

    public void next() {
        Timber.d("Next called.");
        if (mCurrentIndex < mItems.size() - 1) {
            stop();
            resetPlayerErrors();
            mCurrentIndex++;
            videoQueueManager.getValue().setCurrentMediaPosition(mCurrentIndex);
            Timber.i("Moving to index: %d out of %d total items.", mCurrentIndex, mItems.size());
            spinnerOff = false;
            play(0);
        }
    }

    public void prev() {
        Timber.d("Prev called.");
        if (mCurrentIndex > 0 && mItems.size() > 0) {
            stop();
            resetPlayerErrors();
            mCurrentIndex--;
            videoQueueManager.getValue().setCurrentMediaPosition(mCurrentIndex);
            Timber.i("Moving to index: %d out of %d total items.", mCurrentIndex, mItems.size());
            spinnerOff = false;
            play(0);
        }
    }

    public void fastForward() {
        UserSettingPreferences prefs = KoinJavaComponent.<UserSettingPreferences>get(UserSettingPreferences.class);
        skip(prefs.get(UserSettingPreferences.Companion.getSkipForwardLength()));
    }

    public void rewind() {
        UserSettingPreferences prefs = KoinJavaComponent.<UserSettingPreferences>get(UserSettingPreferences.class);
        skip(-prefs.get(UserSettingPreferences.Companion.getSkipBackLength()));
    }

    public void seek(long pos) {
        seek(pos, false);
    }

    public void seek(long pos, boolean skipToNext) {
        if (pos <= 0) pos = 0;
        if (growingRecording && hasInitializedVideoManager()) {
            // Only within what's written so far; the end plays the latest part, it isn't the end
            pos = GrowingRecordingKt.growingSeekTarget(pos, mVideoManager.getDuration());
            skipToNext = false;
        }

        Timber.i("Trying to seek from %s to %d", mCurrentPosition, pos);
        Timber.d("Container: %s", mCurrentStreamInfo == null ? "unknown" : mCurrentStreamInfo.getContainer());

        if (!hasInitializedVideoManager()) {
            return;
        }

        if (wasSeeking) {
            Timber.d("Previous seek has not finished - cancelling seek from %s to %d", mCurrentPosition, pos);
            if (isPaused()) {
                refreshCurrentPosition();
                play(mCurrentPosition);
            }
            return;
        }
        wasSeeking = true;
        
        // If in SyncPlay group and not responding to a SyncPlay command, send seek request to server
        org.jellyfin.androidtv.data.syncplay.SyncPlayManager manager = syncPlayManager.getValue();
        if (manager.getState().getValue().getGroupInfo() != null && !isRespondingToSyncPlayCommand) {
            Timber.i("SyncPlay: Sending seek request to server for position %d", pos);
            final long positionTicks = SyncPlayUtils.msToTicks(pos);
            executeSyncPlayCommand(() -> {
                try {
                    kotlinx.coroutines.BuildersKt.runBlocking(
                        kotlinx.coroutines.Dispatchers.getIO(),
                        (scope, continuation) -> manager.requestSeek(positionTicks, continuation)
                    );
                } catch (Exception e) {
                    Timber.e(e, "Failed to sync seek with SyncPlay");
                }
            });
        }

        // Stop playback when the requested seek position is at the end of the video
        if (skipToNext && pos >= (getDuration() - 100)) {
            // Since we've skipped ahead, set the current position so the PlaybackStopInfo will report the correct end time
            mCurrentPosition = getDuration();
            // Make sure we also set the seek positions so mCurrentPosition won't get overwritten in refreshCurrentPosition()
            currentSkipPos = mCurrentPosition;
            mSeekPosition = mCurrentPosition;
            // Finalize item playback
            itemComplete();
            return;
        }

        if (pos >= getDuration()) pos = getDuration();

        // set seekPosition so real position isn't used until playback starts again
        mSeekPosition = pos;

        if (mCurrentStreamInfo == null) return;

        // rebuild the stream
        // if an older device uses exoplayer to play a transcoded stream but falls back to the generic http stream instead of hls, rebuild the stream
        if (!mVideoManager.isSeekable() && growingRecording) {
            // Asking the server again from the position would drop the part before it
            Timber.w("Growing recording not seekable yet - seek to %d dropped", pos);
            wasSeeking = false;
            return;
        }
        if (!mVideoManager.isSeekable()) {
            Timber.d("Seek method - rebuilding the stream");
            //mkv transcodes require re-start of stream for seek
            mVideoManager.stopPlayback();
            mPlaybackState = PlaybackState.BUFFERING;

            playbackManager.getValue().changeVideoStream(mFragment, mCurrentStreamInfo, mCurrentOptions, pos * 10000, new Response<StreamInfo>(mFragment.getLifecycle()) {
                @Override
                public void onResponse(StreamInfo response) {
                    if (!isActive()) return;
                    mCurrentStreamInfo = response;
                    if (mVideoManager != null) {
                        // Get server-specific API client for subtitle URLs with current user context
                        ApiClient subtitleApi = api.getValue();
                        if (mCurrentOptions.getServerId() != null) {
                            UUID currentUserId = sessionRepository.getValue().getCurrentSession().getValue() != null ? 
                                sessionRepository.getValue().getCurrentSession().getValue().getUserId() : null;
                            ApiClient serverApi = currentUserId != null ?
                                apiClientFactory.getValue().getApiClient(mCurrentOptions.getServerId(), currentUserId) :
                                apiClientFactory.getValue().getApiClientForServer(mCurrentOptions.getServerId());
                            if (serverApi != null) {
                                subtitleApi = serverApi;
                                Timber.d("PlaybackController: Using server-specific API for subtitles (changeVideoStream): %s user: %s", mCurrentOptions.getServerId(), currentUserId);
                            }
                        }
                        mVideoManager.setMediaStreamInfo(subtitleApi, response);
                        mVideoManager.start();
                    }
                    wasSeeking = false;
                }

                @Override
                public void onError(Exception exception) {
                    if (!isActive()) return;
                    wasSeeking = false;
                    if (mFragment != null)
                        Utils.showToast(mFragment.getContext(), R.string.msg_video_playback_error);
                    Timber.e(exception, "Error trying to seek transcoded stream");
                    // call stop so playback can be retried by the user
                    stop();
                }
            });
        } else {
            boolean shouldResumeAfterSeek = mPlaybackState == PlaybackState.PLAYING;
            mPlaybackState = PlaybackState.SEEKING;
            if (mVideoManager.seekTo(pos) < 0) {
                wasSeeking = false;
                if (mFragment != null)
                    Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.seek_error));
                pause();
            } else {
                wasSeeking = false;
                if (isRespondingToSyncPlayCommand) {
                    mPlaybackState = PlaybackState.PAUSED;
                    if (mFragment != null) mFragment.setFadingEnabled(false);
                } else if (shouldResumeAfterSeek) {
                    mVideoManager.play();
                    mPlaybackState = PlaybackState.PLAYING;
                    if (mFragment != null) mFragment.setFadingEnabled(true);
                    startReportLoop();
                } else {
                    mPlaybackState = PlaybackState.PAUSED;
                    if (mFragment != null) mFragment.setFadingEnabled(false);
                }
            }
        }
    }

    private long currentSkipPos = 0;
    private final Runnable skipRunnable = () -> {
        if (!(isPlaying() || isPaused() || mPlaybackState == PlaybackState.SEEKING)) return; // in case we completed since this was requested

        seek(currentSkipPos);
        currentSkipPos = 0;
    };

    private void skip(int msec) {
        if (hasInitializedVideoManager() && (isPlaying() || isPaused() || mPlaybackState == PlaybackState.SEEKING) && spinnerOff && mVideoManager.getCurrentPosition() > 0) { //guard against skipping before playback has truly begun
            mHandler.removeCallbacks(skipRunnable);
            refreshCurrentPosition();
            currentSkipPos = Utils.getSafeSeekPosition((currentSkipPos == 0 ? mCurrentPosition : currentSkipPos) + msec, getDuration());

            Timber.i("Skip amount requested was %s. Calculated position is %s", msec, currentSkipPos);
            Timber.i("Duration reported as: %s current pos: %s", getDuration(), mCurrentPosition);

            mSeekPosition = currentSkipPos;
            mHandler.postDelayed(skipRunnable, 800);
        }
    }

    public void updateTvProgramInfo() {
        // Get the current program info when playing a live TV channel
        final BaseItemDto channel = getCurrentlyPlayingItem();
        if (channel.getType() == BaseItemKind.TV_CHANNEL) {
            PlaybackControllerHelperKt.getLiveTvChannel(this, channel.getId(), updatedChannel -> {
                mItems = PlaybackControllerHelperKt.withRefreshedProgram(mItems, mCurrentIndex, updatedChannel);
                BaseItemDto program = updatedChannel.getCurrentProgram();
                if (program != null) {
                    mCurrentProgramEnd = program.getEndDate();
                    mCurrentProgramStart = program.getStartDate();
                    if (mFragment != null) mFragment.updateDisplay();
                }
                return null;
            });
        }
    }

    private long getRealTimeProgress() {
        if (mCurrentProgramStart != null) {
            return Duration.between(mCurrentProgramStart, LocalDateTime.now()).toMillis();
        }
        return 0;
    }

    private long getTimeShiftedProgress() {
        refreshCurrentPosition();
        return !directStreamLiveTv ? mCurrentPosition + (mCurrentTranscodeStartTime - (mCurrentProgramStart == null ? 0 : mCurrentProgramStart.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())) : getRealTimeProgress();
    }

    private void startReportLoop() {
        if (mCurrentStreamInfo == null) return;

        stopReportLoop();
        reportingHelper.getValue().reportProgress(mFragment, this, getCurrentlyPlayingItem(), getCurrentStreamInfo(), mCurrentPosition * 10000, false);
        mReportLoop = new Runnable() {
            @Override
            public void run() {
                if (isPlaying()) {
                    refreshCurrentPosition();
                    long currentTime = isLiveTv ? getTimeShiftedProgress() : mCurrentPosition;

                    reportingHelper.getValue().reportProgress(mFragment, PlaybackController.this, getCurrentlyPlayingItem(), getCurrentStreamInfo(), currentTime * 10000, false);
                }
                if (mPlaybackState != PlaybackState.UNDEFINED && mPlaybackState != PlaybackState.IDLE) {
                    mHandler.postDelayed(this, PROGRESS_REPORTING_INTERVAL);
                }
            }
        };
        mHandler.postDelayed(mReportLoop, PROGRESS_REPORTING_INTERVAL);
    }

    private void startPauseReportLoop() {
        stopReportLoop();
        if (mCurrentStreamInfo == null) return;
        reportingHelper.getValue().reportProgress(mFragment, this, getCurrentlyPlayingItem(), mCurrentStreamInfo, mCurrentPosition * 10000, true);
        mReportLoop = new Runnable() {
            @Override
            public void run() {
                BaseItemDto currentItem = getCurrentlyPlayingItem();
                if (currentItem == null) {
                    // Loop was called while nothing was playing!
                    stopReportLoop();
                    return;
                }

                if (mPlaybackState != PlaybackState.PAUSED) {
                    // Playback is not paused anymore, stop reporting
                    return;
                }
                refreshCurrentPosition();
                long currentTime = isLiveTv ? getTimeShiftedProgress() : mCurrentPosition;
                if (isLiveTv && !directStreamLiveTv && mFragment != null) {
                    mFragment.setSecondaryTime(getRealTimeProgress());
                }

                reportingHelper.getValue().reportProgress(mFragment, PlaybackController.this, currentItem, getCurrentStreamInfo(), currentTime * 10000, true);
                mHandler.postDelayed(this, PROGRESS_REPORTING_PAUSE_INTERVAL);
            }
        };
        mHandler.postDelayed(mReportLoop, PROGRESS_REPORTING_PAUSE_INTERVAL);
    }

    private void stopReportLoop() {
        if (mHandler != null && mReportLoop != null) {
            mHandler.removeCallbacks(mReportLoop);
        }
    }

    private void initialSeek(final long position) {
        final long askedAt = Instant.now().toEpochMilli();
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mVideoManager == null)
                    return;
                if (growingRecording && !GrowingRecordingKt.growingResumeReady(position, mVideoManager.getDuration(), Instant.now().toEpochMilli() - askedAt)) {
                    // The server's remux of the growing file hasn't reached the position yet
                    mHandler.postDelayed(this, 500);
                } else if (mVideoManager.getDuration() <= 0) {
                    // use mVideoManager.getDuration here for accurate results
                    // wait until we have valid duration
                    mHandler.postDelayed(this, 25);
                } else if (mVideoManager.isSeekable()) {
                    seek(position);
                } else {
                    finishedInitialSeek = true;
                }
            }
        });
    }

    private static final int GROWING_RESTARTS = 3;

    /**
     * Watch live on a recording still recording: the player starts at the end of what the server
     * has remuxed so far, which on a long recording is still catching up (it reads the file from
     * 0:00). Once it grows at about real time, go to the live end ([GrowingRecordingKt#growingCaughtUp]).
     */
    private void growingSeekToLive() {
        final long askedAt = Instant.now().toEpochMilli();
        mHandler.post(new Runnable() {
            long sinceAt = 0;
            long sinceDuration = 0;

            @Override
            public void run() {
                if (mVideoManager == null || !growingRecording) return;
                long now = Instant.now().toEpochMilli();
                long duration = mVideoManager.getDuration();
                if (sinceAt == 0 || duration <= 0) {
                    sinceAt = now;
                    sinceDuration = duration;
                } else if (now - sinceAt >= 5000) {
                    if (GrowingRecordingKt.growingCaughtUp(duration - sinceDuration, now - sinceAt) || now - askedAt >= GrowingRecordingKt.GROWING_RESUME_WAIT_MS) {
                        Timber.i("Growing recording: %d ms written, going live after %d ms", duration, now - askedAt);
                        jumpToLive();
                        return;
                    }
                    sinceAt = now;
                    sinceDuration = duration;
                }
                mHandler.postDelayed(this, 500);
            }
        });
    }

    /** Jump to live on a recording still recording: its live end, in this player (#239). */
    public void jumpToLive() {
        if (growingRecording) seek(GrowingRecordingKt.GROWING_LIVE_EDGE_MS);
    }

    private void itemComplete() {
        interactionTracker.onEpisodeWatched();
        stop();
        resetPlayerErrors();

        BaseItemDto nextItem = getNextItem();
        BaseItemDto curItem = getCurrentlyPlayingItem();
        if (nextItem == null || curItem == null) {
            endPlayback(true);
            return;
        }

        Timber.i("Moving to next queue item. Index: %s", (mCurrentIndex + 1));
        boolean stillWatchingEnabled = userPreferences.getValue().get(UserPreferences.Companion.getStillWatchingBehavior()) != StillWatchingBehavior.DISABLED;
        boolean nextUpEnabled = userPreferences.getValue().get(UserPreferences.Companion.getNextUpBehavior()) != NextUpBehavior.DISABLED;
        if ((stillWatchingEnabled || nextUpEnabled) && curItem.getType() != BaseItemKind.TRAILER) {
            mCurrentIndex++;
            videoQueueManager.getValue().setCurrentMediaPosition(mCurrentIndex);
            spinnerOff = false;

            if (mFragment != null) {
                if (stillWatchingEnabled && interactionTracker.getShowStillWatching()) {
                    // Show "Still Watching" fragment
                    mFragment.showStillWatching(nextItem.getId());
                } else if (nextUpEnabled) {
                    // Show "Next Up" fragment
                    mFragment.showNextUp(nextItem.getId());
                }
            }
            endPlayback();
        } else {
            next();
        }
    }

    @Override
    public void onPlaybackSpeedChange(float newSpeed) {
        // TODO, implement speed change handling
    }

    @Override
    public void onPrepared() {
        // Guard against race condition where playback is stopped before onPrepared callback
        // This can happen during rapid playlist navigation
        if (mCurrentStreamInfo == null) {
            Timber.w("onPrepared called but mCurrentStreamInfo is null - playback may have been stopped");
            return;
        }

        if (mPlaybackState == PlaybackState.BUFFERING) {
            if (mFragment != null) {
                mFragment.setFadingEnabled(true);
                mFragment.leanbackOverlayFragment.setShouldShowOverlay(false);
            }

            mPlaybackState = PlaybackState.PLAYING;
            interactionTracker.notifyStart(getCurrentlyPlayingItem());
            mCurrentTranscodeStartTime = mCurrentStreamInfo.getPlayMethod() == PlayMethod.TRANSCODE ? Instant.now().toEpochMilli() : 0;
            startReportLoop();
        }

        Timber.i("Play method: %s", mCurrentStreamInfo.getPlayMethod() == PlayMethod.TRANSCODE ? "Trans" : "Direct");

        if (mPlaybackState == PlaybackState.PAUSED) {
            mPlaybackState = PlaybackState.PLAYING;
        } else {
            if (!burningSubs) {
                // Make sure the requested subtitles are enabled when external/embedded
                Integer currentSubtitleIndex = mCurrentOptions.getSubtitleStreamIndex();
                if (currentSubtitleIndex == null) currentSubtitleIndex = -1;
                PlaybackControllerHelperKt.setSubtitleIndex(this, currentSubtitleIndex, true);
            } else {
                PlaybackControllerHelperKt.disableDefaultSubtitles(this);
            }

            // select an audio track
            int eligibleAudioTrack = mDefaultAudioIndex;

            // if track switching is done without rebuilding the stream, mCurrentOptions is updated
            // otherwise, use the server default
            if (mCurrentOptions.getAudioStreamIndex() != null) {
                eligibleAudioTrack = mCurrentOptions.getAudioStreamIndex();
            } else if (getCurrentMediaSource() != null && getCurrentMediaSource().getDefaultAudioStreamIndex() != null) {
                eligibleAudioTrack = getCurrentMediaSource().getDefaultAudioStreamIndex();
            }
            // Clear pre-set index so switchAudioStream queries ExoPlayer's actual track
            mCurrentOptions.setAudioStreamIndex(null);
            if (!isTranscoding() && hasInitializedVideoManager() && getAudioStreamIndex() < 0) {
                // The player can't map what it is playing back to a Jellyfin stream index (e.g. an
                // HLS stream with a separate audio rendition, as Tentacle's YouTube items are).
                // switchAudioStream() would then fail to switch in place and restart playback to
                // "apply" the track, and the restarted stream reports -1 again: it restarted every
                // few seconds and never played. Switch in place if possible, otherwise keep playing.
                MediaSourceInfo source = getCurrentMediaSource();
                if (source != null) mVideoManager.setExoPlayerTrack(eligibleAudioTrack, MediaStreamType.AUDIO, source.getMediaStreams());
                mCurrentOptions.setAudioStreamIndex(eligibleAudioTrack);
            } else {
                switchAudioStream(eligibleAudioTrack);
            }
        }

        // Force disable subtitles if preference is enabled and default is None
        boolean subtitlesDefaultToNone = userPreferences.getValue().get(UserPreferences.Companion.getSubtitlesDefaultToNone());
        if (subtitlesDefaultToNone && (mCurrentOptions.getSubtitleStreamIndex() == null || mCurrentOptions.getSubtitleStreamIndex() == -1)) {
            PlaybackControllerHelperKt.disableDefaultSubtitles(this);
        }
    }

    @Override
    public void onError() {
        // Ignore errors raised after playback already stopped — e.g. the "Player release timed
        // out" error ExoPlayer fires while a wedged decoder is being torn down. Retrying here
        // would restart playback the user has already left.
        if (mPlaybackState == PlaybackState.IDLE || mPlaybackState == PlaybackState.UNDEFINED || mPlaybackState == PlaybackState.ERROR) {
            Timber.i("Ignoring player error in state %s - playback is not active", mPlaybackState);
            return;
        }

        if (mFragment == null) {
            playerErrorEncountered();
            return;
        }

        androidx.media3.common.PlaybackException error =
                hasInitializedVideoManager() ? mVideoManager.getLastPlayerError() : null;

        // Live TV: prepare the same stream again first. A new PlaybackInfo opens another tuner
        // consumer and kills the running transcode, which made every hiccup a visible gap (#19).
        if (isLiveTv && !liveReprepareTried && error != null
                && PlayerErrorPolicy.canReprepareLiveInPlace(error.errorCode, PlayerErrorPolicy.playerErrorHttpStatus(error))) {
            liveReprepareTried = true;
            Timber.i("Live stream error %s - preparing the same stream again", error.getErrorCodeName());
            mVideoManager.reprepare(error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW);
            return;
        }

        // A server stream that timed out before its first frame may still be in preparation
        // (subtitles cut out of a remote file took minutes): prepare the same stream again, which
        // joins the server's pending encode instead of starting another one (H1).
        boolean serverStream = mCurrentStreamInfo != null && mCurrentStreamInfo.getPlayMethod() != PlayMethod.DIRECT_PLAY;
        if (error != null && PlayerErrorPolicy.canWaitForServerStart(isLiveTv, serverStream, streamHasPlayed, serverStartWaits, error.errorCode)) {
            boolean subtitles = burningSubs || RemoteSubtitlePolicy.subtitleDelivery(getCurrentMediaSource(),
                    mCurrentOptions != null ? mCurrentOptions.getSubtitleStreamIndex() : null) == SubtitleDeliveryMethod.ENCODE;
            StartWaitMessage message = PlayerErrorPolicy.startWaitMessage(serverStartWaits, subtitles);
            serverStartWaits++;
            Timber.i("Server stream not ready (%s) - preparing it again, wait %d of %d",
                    error.getErrorCodeName(), serverStartWaits, PlayerErrorPolicy.SERVER_START_WAITS);
            int text = message == StartWaitMessage.SUBTITLES ? R.string.msg_preparing_subtitles
                    : message == StartWaitMessage.STREAM ? R.string.msg_preparing_stream
                    : R.string.msg_still_preparing;
            Utils.showToast(mFragment.getContext(), mFragment.getString(text));
            mVideoManager.reprepare(false);
            return;
        }

        // A remote source the app plays itself (a Tentacle YouTube or VOD link) that the server
        // refused with a reason: say why and stop. Every rung of the retry ladder would ask the
        // same server again (#24, #44).
        if (!isLiveTv && error != null && mCurrentStreamInfo != null
                && mCurrentStreamInfo.getPlayMethod() == PlayMethod.DIRECT_PLAY
                && mCurrentStreamInfo.getMediaSource() != null
                && Boolean.TRUE.equals(mCurrentStreamInfo.getMediaSource().isRemote())) {
            String detail = PlayerErrorPolicy.playerErrorDetail(error);
            if (detail != null) {
                Timber.w("Remote source refused: %s", detail);
                mPlaybackState = PlaybackState.ERROR;
                Utils.showToast(mFragment.getContext(), detail);
                mFragment.closePlayer();
                return;
            }
        }

        if (isLiveTv && directStreamLiveTv) {
            Utils.showToast(mFragment.getContext(), mFragment.getString(R.string.msg_error_live_stream));
            // And the server's plain words when it knows what is wrong (Tentacle FamilyStatus)
            LiveTvStatusNotice.onLiveTvError(mFragment, null);
            directStreamLiveTv = false;
        } else {
            String msg = mFragment.getString(R.string.video_error_unknown_error);
            Timber.e("Playback error - %s", msg);
        }
        playerErrorEncountered();
    }

    @Override
    public void onCompletion() {
        Timber.i("On Completion fired");
        if (growingRecording && !growingFinished && mPlaybackState != PlaybackState.IDLE) {
            // The server's remux ended: the recording finished, or its file stopped growing for
            // a while (Jellyfin stops reading after 30 s). Still recording: open it again there.
            final long at = hasInitializedVideoManager() ? Math.max(mCurrentPosition, mVideoManager.getCurrentPosition()) : mCurrentPosition;
            PlaybackControllerHelperKt.isStillRecording(this, getCurrentlyPlayingItem(), stillRecording -> {
                if (mFragment == null || mPlaybackState == PlaybackState.IDLE) return null;
                if (stillRecording && growingRestarts < GROWING_RESTARTS) {
                    growingRestarts++;
                    Timber.w("Growing recording's stream ended at %d ms while it records - opening it again (%d)", at, growingRestarts);
                    mCurrentPosition = at;
                    refreshStream();
                } else {
                    growingFinished = true;
                    itemComplete();
                }
                return null;
            });
            return;
        }
        if (isLiveTv && (mPlaybackState == PlaybackState.PLAYING || mPlaybackState == PlaybackState.BUFFERING)) {
            // A live channel doesn't end: an ended playlist means the server's remux stopped
            // (the provider dropped out). Retry like any stream error instead of closing the
            // player without a word (#60).
            Timber.w("Live stream ended on the server - retrying");
            playerErrorEncountered();
            return;
        }
        // The media source's runtime (the server's probe of this file or stream), not the
        // item's: a .strm's metadata runtime can be longer than the stream itself.
        MediaSourceInfo source = getCurrentMediaSource();
        long runtime = source != null && source.getRunTimeTicks() != null ? source.getRunTimeTicks() / 10000 : -1;
        long position = hasInitializedVideoManager() ? Math.max(mCurrentPosition, mVideoManager.getCurrentPosition()) : mCurrentPosition;
        if (mPlaybackState != PlaybackState.IDLE && PlayerErrorPolicy.endedEarly(isLiveTv, position, runtime)) {
            Timber.w("Stream ended at %d of %d ms - resuming there instead of moving on", position, runtime);
            mCurrentPosition = position;
            playerErrorEncountered();
            return;
        }
        itemComplete();
    }

    @Override
    public void onProgress() {
        refreshCurrentPosition();
        if (isPlaying()) {
            progressSinceError = true;
            streamHasPlayed = true;
            if (isLiveTv) {
                liveProgressAt = Instant.now().toEpochMilli();
                liveHasPlayed = true;
                liveReprepareTried = false;
            }
            if (!spinnerOff) {
                if (growingStartAtLive) {
                    growingStartAtLive = false;
                    if (growingRecording) growingSeekToLive();
                } else if (mStartPosition > 0) {
                    initialSeek(mStartPosition);
                    mStartPosition = 0;
                } else {
                    finishedInitialSeek = true;
                    stopSpinner();
                }
            }
        }
        if (mFragment != null)
            mFragment.setCurrentTime(mCurrentPosition);
    }

    public long getDuration() {
        long duration = 0;

        if (hasInitializedVideoManager()) {
            duration = mVideoManager.getDuration();
        } else if (getCurrentMediaSource() != null && getCurrentMediaSource().getRunTimeTicks() != null) {
            duration = getCurrentMediaSource().getRunTimeTicks() / 10000;
        } else if (getCurrentlyPlayingItem() != null && getCurrentlyPlayingItem().getRunTimeTicks() != null) {
            duration = getCurrentlyPlayingItem().getRunTimeTicks() / 10000;
        }
        return duration > 0 ? duration : 0;
    }

    public long getBufferedPosition() {
        long bufferedPosition = -1;

        if (hasInitializedVideoManager())
            bufferedPosition = mVideoManager.getBufferedPosition();

        if (bufferedPosition < 0)
            bufferedPosition = getDuration();

        return bufferedPosition;
    }

    public long getCurrentPosition() {
        if (currentSkipPos > 0) return currentSkipPos;
        return !isPlaying() && mSeekPosition != -1 ? mSeekPosition : mCurrentPosition;
    }

    public boolean isPaused() {
        return mPlaybackState == PlaybackState.PAUSED;
    }

    public @NonNull ZoomMode getZoomMode() {
        return hasInitializedVideoManager() ? mVideoManager.getZoomMode() : ZoomMode.FIT;
    }

    public void setZoom(@NonNull ZoomMode mode) {
        if (hasInitializedVideoManager())
            mVideoManager.setZoom(mode);
    }

    /**
     * List of various states that we can be in
     */
    public enum PlaybackState {
        PLAYING,
        PAUSED,
        BUFFERING,
        IDLE,
        SEEKING,
        UNDEFINED,
        ERROR
    }
}
