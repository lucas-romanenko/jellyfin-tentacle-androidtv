package org.jellyfin.androidtv.ui.livetv;

import static org.koin.java.KoinJavaComponent.inject;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.graphics.Color;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.constant.CustomMessage;
import org.jellyfin.androidtv.data.repository.CustomMessageRepository;
import org.jellyfin.androidtv.databinding.LiveTvGuideBinding;
import org.jellyfin.androidtv.ui.AsyncImageView;
import org.jellyfin.androidtv.ui.GuideChannelHeader;
import org.jellyfin.androidtv.ui.GuidePagingButton;
import org.jellyfin.androidtv.ui.HorizontalScrollViewListener;
import org.jellyfin.androidtv.ui.LiveProgramDetailPopup;
import org.jellyfin.androidtv.ui.ObservableHorizontalScrollView;
import org.jellyfin.androidtv.ui.ObservableScrollView;
import org.jellyfin.androidtv.ui.ProgramGridCell;
import org.jellyfin.androidtv.ui.ScrollViewListener;
import org.jellyfin.androidtv.ui.playback.PlaybackLauncher;
import org.jellyfin.androidtv.util.CoroutineUtils;
import org.jellyfin.androidtv.util.DateTimeExtensionsKt;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.ui.browsing.composable.inforow.BaseItemInfoRowView;
import org.jellyfin.androidtv.util.InfoLayoutHelper;
import org.jellyfin.androidtv.util.PlaybackHelper;
import org.jellyfin.androidtv.util.TextUtilsKt;
import org.jellyfin.androidtv.util.TimeUtils;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.androidtv.util.apiclient.EmptyResponse;
import org.jellyfin.androidtv.util.apiclient.Response;
import org.jellyfin.sdk.model.api.BaseItemDto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import kotlin.Lazy;
import kotlinx.coroutines.flow.MutableStateFlow;
import timber.log.Timber;

public class LiveTvGuideFragment extends Fragment implements LiveTvGuide, View.OnKeyListener {
    public static final int GUIDE_ROW_HEIGHT_DP = 55;
    public static final int GUIDE_ROW_WIDTH_PER_MIN_DP = 7;
    /** The most channels on one guide page (and per programs request, TvManagerHelper). */
    public static final int PAGE_SIZE = 75;
    public static final int NORMAL_HOURS = 9;
    public static final int FILTERED_HOURS = 4;

    private TextView mDisplayDate;
    private TextView mTitle;
    private TextView mChannelStatus;
    private TextView mFilterStatus;
    private TextView mSummary;
    private AsyncImageView mImage;
    private LinearLayout mInfoRow;
    private LinearLayout mChannels;
    private LinearLayout mTimeline;
    private LinearLayout mProgramRows;
    private ObservableScrollView mChannelScroller;
    private HorizontalScrollView mTimelineScroller;
    private View mSpinner;
    private View mResetButton;
    private TextView mProgramTime;
    private TextView mViewFavorites;
    private TextView mViewAll;
    private LinearLayout mDays;
    private View mEmptyFavorites;
    private View mShowAllButton;
    private View mNowLine;
    private TextView mNowPill;

    // Favorites / All: null until the saved choice is read (Jellyfin display preferences, shared with the web)
    private GuideView mView = null;
    private int mFavoriteCount = -1;
    private int mAllCount = -1;
    // When the guide data ends (furthest programme end in this view); null = unknown
    private LocalDateTime mFurthestEnd;
    private List<LocalDate> mGuideDays = new ArrayList<>();
    // A load started before the last toggle answers into nothing
    private int mLoadGeneration = 0;
    // All shows its first page before the whole list has come (mAllChannels is that page until then)
    private boolean mPartialList = false;
    private boolean mRestLoading = false;
    // All shown from the list read last time: read it again once the first screen is up
    private boolean mRelistAfterPaint = false;
    // When load() started, for the GuideTiming lines (0: the first screen of this load is logged)
    private long mLoadStartMs = 0;
    // After Favorites / All, focus stays on the toggle instead of jumping into the grid
    private boolean mKeepHeaderFocus = false;

    BaseItemDto mSelectedProgram;
    RelativeLayout mSelectedProgramView;

    private List<BaseItemDto> mAllChannels;
    private UUID mFirstFocusChannelId;
    private boolean focusAtEnd;
    private GuideFilters mFilters = new GuideFilters();

    private LocalDateTime mCurrentGuideStart = LocalDateTime.now();
    private LocalDateTime mCurrentGuideEnd;
    private int mCurrentDisplayChannelStartNdx = 0;
    private int mCurrentDisplayChannelEndNdx = 0;

    // Channels on one page: fewer on a small-memory TV (GuidePageSize.kt), where building 75
    // rows of program cells at once took seconds and most of the heap.
    private int pageSize = PAGE_SIZE;

    private int guideRowHeightPx;
    private int guideRowWidthPerMinPx;

    private Handler mHandler = new Handler();

    private final Lazy<CustomMessageRepository> customMessageRepository = inject(CustomMessageRepository.class);
    private final Lazy<PlaybackHelper> playbackHelper = inject(PlaybackHelper.class);
    private final Lazy<ImageHelper> imageHelper = inject(ImageHelper.class);
    private final Lazy<PlaybackLauncher> playbackLauncher = inject(PlaybackLauncher.class);
    private MutableStateFlow<Boolean> showOptions;
    private MutableStateFlow<Boolean> showFilterOptions;

    // Favourite and lineup changes while the guide is open, applied in place (GuideLiveUpdates)
    private GuideLiveUpdates mLiveUpdates;
    private ObservableScrollView mProgramVScroller;
    // Programmes of a page are on their way (paging, a new day): the rows are about to be replaced
    private boolean mProgramsLoading = false;
    private int mProgramsToken = 0;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        android.util.Log.i("GuideTiming", "guide created at " + android.os.SystemClock.uptimeMillis());
        mLiveUpdates = new GuideLiveUpdates(this);
        mLiveUpdates.start();
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        guideRowHeightPx = Utils.convertDpToPixel(requireContext(), GUIDE_ROW_HEIGHT_DP);
        guideRowWidthPerMinPx = Utils.convertDpToPixel(requireContext(), GUIDE_ROW_WIDTH_PER_MIN_DP);
        pageSize = GuidePageSizeKt.guidePageSize(requireContext());

        LiveTvGuideBinding binding = LiveTvGuideBinding.inflate(getLayoutInflater(), container, false);

        mDisplayDate = binding.displayDate;
        mTitle = binding.title;
        mSummary = binding.summary;
        mChannelStatus = binding.channelsStatus;
        mFilterStatus = binding.filterStatus;
        mChannelStatus.setTextColor(Color.GRAY);
        mFilterStatus.setTextColor(Color.GRAY);
        mInfoRow = binding.infoRow;
        mImage = binding.programImage;
        mChannels = binding.channels;
        mTimeline = binding.timeline;
        mProgramRows = binding.programRows;
        mSpinner = binding.spinner;
        mSpinner.setVisibility(View.VISIBLE);

        showFilterOptions = LiveTvGuideFragmentHelperKt.addSettingsFilters(this, binding);
        View mFilterButton = binding.filterButton;
        mFilterButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showFilterOptions.setValue(true);
            }
        });
        mFilterButton.setContentDescription(getString(R.string.lbl_filters));

        showOptions = LiveTvGuideFragmentHelperKt.addSettingsOptions(this, binding);
        View mOptionsButton = binding.optionsButton;
        mOptionsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showOptions.setValue(true);
            }
        });
        mOptionsButton.setContentDescription(getString(R.string.lbl_other_options));

        mProgramTime = binding.programTime;
        mDays = binding.days;
        mEmptyFavorites = binding.emptyFavorites;
        mShowAllButton = binding.showAllButton;
        mNowLine = binding.nowLine;
        mNowPill = binding.nowPill;
        mViewFavorites = binding.viewFavorites;
        mViewAll = binding.viewAll;
        mViewFavorites.setOnClickListener(v -> setView(GuideView.FAVORITES));
        mViewAll.setOnClickListener(v -> setView(GuideView.ALL));
        mShowAllButton.setOnClickListener(v -> setView(GuideView.ALL));
        LiveTvGuideFragmentHelperKt.setupGuideLinks(this, binding);
        updateToggle();

        mResetButton = binding.resetButton;
        mResetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                pageGuideTo(LocalDateTime.now());
            }
        });

        mProgramRows.setFocusable(false);
        mChannelScroller = binding.channelScroller;
        ObservableScrollView programVScroller = binding.programVScroller;
        mProgramVScroller = programVScroller;
        programVScroller.setScrollViewListener(new ScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                mChannelScroller.scrollTo(x, y);
            }
        });
        mChannelScroller.setScrollViewListener(new ScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableScrollView scrollView, int x, int y, int oldx, int oldy) {
                programVScroller.scrollTo(x, y);
            }
        });

        mTimelineScroller = binding.timelineHScroller;
        mTimelineScroller.setFocusable(false);
        mTimelineScroller.setFocusableInTouchMode(false);
        mTimeline.setFocusable(false);
        mTimeline.setFocusableInTouchMode(false);
        mChannelScroller.setFocusable(false);
        mChannelScroller.setFocusableInTouchMode(false);
        ObservableHorizontalScrollView programHScroller = binding.programHScroller;
        programHScroller.setScrollViewListener(new HorizontalScrollViewListener() {
            @Override
            public void onScrollChanged(ObservableHorizontalScrollView scrollView, int x, int y, int oldx, int oldy) {
                mTimelineScroller.scrollTo(x, y);
            }
        });

        programHScroller.setFocusable(false);
        programHScroller.setFocusableInTouchMode(false);

        mChannels.setFocusable(false);
        mChannelScroller.setFocusable(false);

        // Register to receive message from popup
        CoroutineUtils.readCustomMessagesOnLifecycle(getLifecycle(), customMessageRepository.getValue(), message -> {
            if (message.equals(CustomMessage.ActionComplete.INSTANCE)) dismissProgramOptions();
            return null;
        });

        // A known Live TV problem, in the server's words (Tentacle FamilyStatus)
        LiveTvStatusNotice.onGuideOpened(this);

        return binding.getRoot();
    }

    private int getGuideHours() {
        return mFilters.any() ? FILTERED_HOURS : NORMAL_HOURS;
    }

    private void load() {
        final int generation = ++mLoadGeneration;
        mProgramsLoading = false;
        mPartialList = false;
        mRestLoading = false;
        mRelistAfterPaint = false;
        if (mLoadStartMs == 0) {
            mLoadStartMs = android.os.SystemClock.uptimeMillis();
            android.util.Log.i("GuideTiming", "load at " + mLoadStartMs);
        }
        mCurrentGuideStart = GuideModel.guideStartFor(LocalDateTime.now());
        fillTimeLine(mCurrentGuideStart, getGuideHours());
        mSpinner.setVisibility(View.VISIBLE);
        mEmptyFavorites.setVisibility(View.GONE);

        if (mView == null) {
            // The person's last choice first (shared with Tentacle's web guide); Favorites when none
            TvManagerHelperKt.loadGuideView(this, view -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                mView = view;
                timing("view known: " + view);
                load();
                return null;
            });
            return;
        }
        updateToggle();

        if (mView == GuideView.FAVORITES) {
            TvManager.loadFavoriteChannels(this, (channels, allCount) -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                if (channels == null) {
                    onChannelsFailed();
                    return null;
                }
                if (allCount != null) mAllCount = allCount;
                mFavoriteCount = channels.size();
                List<UUID> ids = new ArrayList<>();
                for (BaseItemDto channel : channels) ids.add(channel.getId());
                onChannelsLoaded(channels, GuideModel.indexOfOrZero(ids, TvManager.getLastLiveTvChannel()), generation);
                return null;
            });
        } else if (TvManager.getCachedAllChannels() != null) {
            // Shown at once from the list read last time (also by the player); read again after
            List<BaseItemDto> all = TvManager.getCachedAllChannels();
            mAllCount = all.size();
            mFavoriteCount = countFavorites(all);
            mRelistAfterPaint = true;
            onChannelsLoaded(all, TvManager.lastChannelIndex(all), generation);
        } else if (!mFilters.any()) {
            // The first page first (1,700 channels take seconds to read on a TV), the rest after it shows
            TvManagerHelperKt.loadFirstGuidePage(this, pageSize, (page, allCount, favoriteCount) -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                if (page == null) {
                    onChannelsFailed();
                    return null;
                }
                if (allCount != null) mAllCount = allCount;
                if (favoriteCount != null) mFavoriteCount = favoriteCount;
                mPartialList = allCount == null || allCount > page.size();
                onChannelsLoaded(page, TvManager.lastChannelIndex(page), generation);
                return null;
            });
        } else {
            // Filters look through every channel: the whole list first
            TvManager.loadAllChannels(this, ndx -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                if (ndx < 0 || TvManager.getAllChannels() == null) {
                    onChannelsFailed();
                    return null;
                }
                List<BaseItemDto> all = TvManager.getAllChannels();
                mAllCount = all.size();
                mFavoriteCount = countFavorites(all);
                onChannelsLoaded(all, ndx, generation);
                return null;
            });
        }
    }

    private void onChannelsFailed() {
        mSpinner.setVisibility(View.GONE);
        // The server's plain words when it knows what is wrong, else "Unable to load channels"
        LiveTvStatusNotice.onLiveTvError(this, getString(R.string.msg_unable_load_channels));
    }

    private void onChannelsLoaded(List<BaseItemDto> channels, int lastNdx, int generation) {
        if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
        timing("channels in: " + channels.size());
        mAllChannels = channels;
        updateToggle();

        // The dates on offer stop where this view's guide data stops
        List<UUID> ids = null;
        if (mView == GuideView.FAVORITES) {
            ids = new ArrayList<>();
            for (BaseItemDto channel : channels) ids.add(channel.getId());
        }
        mFurthestEnd = null;
        updateDays();
        if (!channels.isEmpty()) {
            TvManagerHelperKt.loadFurthestProgramEnd(this, ids, end -> {
                if (!isAdded() || generation != mLoadGeneration) return null;
                mFurthestEnd = end;
                updateDays();
                return null;
            });
        }

        if (channels.isEmpty()) {
            showNoChannels(true);
            return;
        }

        int start;
        if (lastNdx >= pageSize) {
            // last channel is not in first page so grab a set where it will be in the middle
            start = lastNdx - (pageSize / 2);
        } else {
            start = 0; // just start at beginning
        }
        displayChannels(start, pageSize);
    }

    /** No channel in this view: Favorites says how to add one and offers All. */
    private void showNoChannels(boolean focusShowAll) {
        if (mView == GuideView.FAVORITES) {
            // One plain line on how to add one, and the way to All (focus there before the rows go)
            mEmptyFavorites.setVisibility(View.VISIBLE);
            if (focusShowAll) mShowAllButton.requestFocus();
        }
        mChannels.removeAllViews();
        mProgramRows.removeAllViews();
        mChannelStatus.setText("");
        mFilterStatus.setText("");
        mNowLine.setVisibility(View.GONE);
        mNowPill.setVisibility(View.GONE);
        mSpinner.setVisibility(View.GONE);
    }

    private static int countFavorites(List<BaseItemDto> channels) {
        int n = 0;
        for (BaseItemDto channel : channels) {
            if (channel.getUserData() != null && channel.getUserData().isFavorite()) n++;
        }
        return n;
    }

    /** Favorites / All: remembered for this person (on the server, so the web follows) and shown. */
    private void setView(GuideView view) {
        if (view == mView) return;
        mView = view;
        TvManagerHelperKt.saveGuideView(this, view);
        mFirstFocusChannelId = TvManager.getLastLiveTvChannel();
        mKeepHeaderFocus = true;
        updateToggle();
        load();
        (view == GuideView.FAVORITES ? mViewFavorites : mViewAll).requestFocus();
    }

    private void updateToggle() {
        if (mViewFavorites == null) return;
        mViewFavorites.setText(LiveTvGuideFragmentHelperKt.guideToggleLabel(getString(R.string.lbl_guide_favorites), mFavoriteCount < 0 ? null : mFavoriteCount));
        mViewAll.setText(LiveTvGuideFragmentHelperKt.guideToggleLabel(getString(R.string.lbl_guide_all), mAllCount < 0 ? null : mAllCount));
        mViewFavorites.setSelected(mView == GuideView.FAVORITES);
        mViewAll.setSelected(mView == GuideView.ALL);
    }

    /** A channel's favourite changed (heart in the popup, or holding OK on its name): new counts. */
    public void onFavoritesChanged() {
        if (mView == GuideView.ALL && mPartialList) {
            // The whole list (on its way) brings the count
        } else if (mView == GuideView.ALL && TvManager.getAllChannels() != null) {
            mFavoriteCount = countFavorites(TvManager.getAllChannels());
        } else if (mView == GuideView.FAVORITES && mAllChannels != null) {
            mFavoriteCount = countFavorites(mAllChannels);
        }
        updateToggle();
        // In Favorites an unstarred channel's row goes now, not only when the server's message comes
        if (mLiveUpdates != null) mLiveUpdates.recompute();
    }

    /** Today up to the last day with guide data; no choice when only today has data. */
    private void updateDays() {
        if (mDays == null || !isAdded()) return;
        LocalDateTime now = LocalDateTime.now();
        mGuideDays = GuideModel.guideDays(now, mFurthestEnd);
        mDays.removeAllViews();
        if (mGuideDays.size() < 2 || mAllChannels == null || mAllChannels.isEmpty()) {
            mDays.setVisibility(View.GONE);
            return;
        }
        mDays.setVisibility(View.VISIBLE);
        LocalDate shown = mCurrentGuideStart.toLocalDate();
        for (int i = 0; i < mGuideDays.size(); i++) {
            final LocalDate day = mGuideDays.get(i);
            TextView chip = new TextView(requireContext(), null, 0, R.style.GuideChip);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (i > 0) params.setMarginStart(Utils.convertDpToPixel(requireContext(), 6));
            chip.setLayoutParams(params);
            chip.setText(GuideModel.guideDayLabel(day, now.toLocalDate(), getString(R.string.lbl_today), getString(R.string.lbl_tomorrow), java.util.Locale.getDefault()));
            chip.setSelected(day.equals(shown));
            chip.setOnClickListener(v -> {
                mKeepHeaderFocus = true;
                pageGuideTo(day.equals(LocalDate.now()) ? LocalDateTime.now() : day.atStartOfDay());
            });
            mDays.addView(chip);
        }
    }

    /** The day chip of the day shown is the chosen one (also after paging into the next day). */
    private void updateDaySelection() {
        if (mDays == null) return;
        LocalDate shown = mCurrentGuideStart.toLocalDate();
        for (int i = 0; i < mDays.getChildCount() && i < mGuideDays.size(); i++) {
            mDays.getChildAt(i).setSelected(mGuideDays.get(i).equals(shown));
        }
    }

    public void refreshFavorite(UUID channelId){
        BaseItemDto channel = TvManager.findChannel(channelId);
        if (channel == null) return;
        for (int i = 0; i < mChannels.getChildCount(); i++) {
            View child = mChannels.getChildAt(i);
            if (!(child instanceof GuideChannelHeader)) continue;
            GuideChannelHeader gch = (GuideChannelHeader) child;
            if (gch.getChannel().getId().equals(channelId)) {
                gch.setChannel(channel);
                gch.refreshFavorite();
            }
        }
        onFavoritesChanged();
    }

    @Override
    public void onResume() {
        super.onResume();

        // Back in front (from the player, another screen, the TV's home): a change meanwhile wasn't
        // heard, so the channels are read once and only what differs changes
        if (!doLoad() && mLiveUpdates != null) mLiveUpdates.relist();
        mHandler.removeCallbacks(mNowTick);
        mHandler.postDelayed(mNowTick, 60_000);
    }

    /** Loads the guide when it is stale, forced or empty; true when it does. */
    protected boolean doLoad() {
        // Read the filters every time: the Filters panel saves them and then calls doLoad(),
        // and with the load in onResume only, a change applied after leaving the guide.
        mFilters.load();
        if (TvManager.shouldForceReload() || mCurrentGuideStart.plusMinutes(30).isBefore(LocalDateTime.now()) || mChannels.getChildCount() == 0) {
            load();

            mFirstFocusChannelId = TvManager.getLastLiveTvChannel();
            return true;
        }
        return false;
    }

    @Override
    public void onPause() {
        super.onPause();

        mHandler.removeCallbacks(mNowTick);
        if (mDisplayProgramsTask != null && mDisplayProgramsTask.getStatus() != AsyncTask.Status.FINISHED) {
            mDisplayProgramsTask.cancel(true);
            // Its rows are only added when it finishes: leave the guide empty so onResume reloads it
            mChannels.removeAllViews();
            mProgramRows.removeAllViews();
        }
        if (mDetailPopup != null) {
            mDetailPopup.dismiss();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (mCurrentGuideStart.isAfter(LocalDateTime.now())) {
            TvManager.forceReload(); //we paged ahead - force a re-load if we come back in
        }
    }

    @Override
    public boolean onKey(View v, int keyCode, KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_UP) return onKeyUp(keyCode, event);
        else if (event.getAction() == KeyEvent.ACTION_DOWN && event.isLongPress()) return onKeyLongPress(keyCode);
        else if (event.getAction() == KeyEvent.ACTION_DOWN) return onKeyDown(keyCode, event);
        return false;
    }

    private boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                event.startTracking();
                return true;
        }
        return false;
    }

    private boolean onKeyLongPress(int keyCode) {
        switch (keyCode){
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if (mSelectedProgramView instanceof ProgramGridCell)
                    showProgramOptions();
                else if(mSelectedProgramView instanceof GuideChannelHeader)
                    LiveTvGuideFragmentHelperKt.toggleFavorite(this);
                return true;
        }
        return false;
    }

    private boolean onKeyUp(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_MENU:
                // bring up filter selection
                showFilterOptions.setValue(true);
                break;
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_DPAD_CENTER:
                if ((event.getFlags() & KeyEvent.FLAG_CANCELED_LONG_PRESS) == 0) {
                    if (mSelectedProgramView instanceof ProgramGridCell) {
                        if (mSelectedProgram.getStartDate().isBefore(LocalDateTime.now()))
                            playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                        else
                            showProgramOptions();
                        return true;
                    } else if (mSelectedProgramView instanceof GuideChannelHeader) {
                        // Tuning directly to a channel
                        GuideChannelHeader channelHeader = (GuideChannelHeader) mSelectedProgramView;
                        playbackHelper.getValue().getItemsToPlay(requireContext(), channelHeader.getChannel(), false, false, new Response<List<BaseItemDto>>(getLifecycle()) {
                            @Override
                            public void onResponse(List<BaseItemDto> response) {
                                if (!isActive()) return;
                                playbackLauncher.getValue().launch(requireContext(), response);
                            }
                        });
                    }
                }
                return false;
            case KeyEvent.KEYCODE_MEDIA_PLAY:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if ((mDetailPopup == null || !mDetailPopup.isShowing())
                        && mSelectedProgram != null
                        && mSelectedProgram.getChannelId() != null) {
                    // tune to the current channel
                    playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                    return true;
                }

            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (requireActivity().getCurrentFocus() instanceof ProgramGridCell
                        && mSelectedProgramView != null
                        && ((ProgramGridCell)mSelectedProgramView).isLast()
                        && GuideModel.hasGuideDataAfter(mCurrentGuideEnd, mFurthestEnd)) {
                    requestGuidePage(mCurrentGuideEnd);
                }
                break;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (requireActivity().getCurrentFocus() instanceof ProgramGridCell
                        && mSelectedProgramView != null
                        && ((ProgramGridCell)mSelectedProgramView).isFirst()
                        && mSelectedProgram.getStartDate().isAfter(LocalDateTime.now())) {
                    focusAtEnd = true;
                    requestGuidePage(mCurrentGuideStart.minusHours(getGuideHours()));
                }
                break;
        }

        return false;
    }

    private void requestGuidePage(final LocalDateTime startTime) {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.lbl_load_guide_data)
                .setMessage(startTime.isAfter(mCurrentGuideStart) ? getString(R.string.msg_live_tv_next, getGuideHours()) : getString(R.string.msg_live_tv_prev, getGuideHours()))
                .setPositiveButton(R.string.lbl_yes, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        pageGuideTo(startTime);
                    }
                })
                .setNegativeButton(R.string.lbl_no, null)
                .show();
    }

    private void pageGuideTo(LocalDateTime startTime) {
        // Never before the current half hour: the guide shows what is on now and later
        if (startTime.isBefore(LocalDateTime.now())) startTime = GuideModel.guideStartFor(LocalDateTime.now());
        Timber.i("page to %s", startTime);
        TvManager.forceReload(); // don't allow cache
        if (mSelectedProgram != null) {
            mFirstFocusChannelId = mSelectedProgram.getChannelId();
        }
        fillTimeLine(startTime, getGuideHours());
        loadProgramData();
    }

    private LiveProgramDetailPopup mDetailPopup;

    public void dismissProgramOptions() {
        if (mDetailPopup != null) {
            mDetailPopup.dismiss();
        }
    }

    public void showProgramOptions() {
        if (mSelectedProgram == null) return;
        if (mDetailPopup == null) {
            mDetailPopup = new LiveProgramDetailPopup(requireActivity(), this, this, mSummary.getWidth()+20, new EmptyResponse(getLifecycle()) {
                @Override
                public void onResponse() {
                    if (!isActive()) return;
                    playbackHelper.getValue().retrieveAndPlay(mSelectedProgram.getChannelId(), false, requireContext());
                }
            });
        }

        // The cell holds the programme as last updated by the popup (record / cancel), while
        // mSelectedProgram may still be the copy from when the guide loaded.
        if (mSelectedProgramView instanceof ProgramGridCell) mSelectedProgram = ((ProgramGridCell) mSelectedProgramView).getProgram();
        mDetailPopup.setContent(mSelectedProgram, ((ProgramGridCell)mSelectedProgramView));
        mDetailPopup.show(mImage, mTitle.getLeft(), mTitle.getTop() - 10);
    }

    public void displayChannels(int start, int max) {
        // GuidePagingButton asks for PAGE_SIZE channels: never more than this TV's page
        int end = start + Math.min(max, pageSize);
        if (end > mAllChannels.size()) {
            end = mAllChannels.size();
        }

        if (mFilters.any()) {
            // if we are filtered, then we need to get programs for all channels
            mCurrentDisplayChannelStartNdx = 0;
            mCurrentDisplayChannelEndNdx = mAllChannels.size()-1;
        } else {
            mCurrentDisplayChannelStartNdx = start;
            mCurrentDisplayChannelEndNdx = end - 1;
        }
        Timber.d("*** Display channels pre-execute");
        mSpinner.setVisibility(View.VISIBLE);

        loadProgramData();
    }

    private void loadProgramData() {
        mProgramRows.removeAllViews();
        mChannels.removeAllViews();
        mChannelStatus.setText("");
        mFilterStatus.setText("");
        if (mAllChannels == null || mAllChannels.isEmpty()) return;
        // The guide's own channels (Favorites or All), one page of them
        int last = Math.min(mCurrentDisplayChannelEndNdx, mAllChannels.size() - 1);
        UUID[] ids = new UUID[Math.max(0, last - mCurrentDisplayChannelStartNdx + 1)];
        for (int i = 0; i < ids.length; i++) ids[i] = mAllChannels.get(mCurrentDisplayChannelStartNdx + i).getId();
        final int generation = mLoadGeneration;
        final int token = ++mProgramsToken;
        mProgramsLoading = true;
        timing("programmes asked: " + ids.length + " channels");
        TvManager.getProgramsAsync(this, ids, mCurrentGuideStart, mCurrentGuideEnd, new EmptyResponse(getLifecycle()) {
            @Override
            public void onResponse() {
                if (token == mProgramsToken) mProgramsLoading = false;
                if (!isActive() || generation != mLoadGeneration) return;
                Timber.d("*** Programs response");
                timing("programmes in");
                if (mDisplayProgramsTask != null) mDisplayProgramsTask.cancel(true);
                mDisplayProgramsTask = new DisplayProgramsTask();
                mDisplayProgramsTask.execute(mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx);
            }
        });
    }

    DisplayProgramsTask mDisplayProgramsTask;
    class DisplayProgramsTask extends AsyncTask<Integer, Integer, Void> {

        View firstFocusView;
        int displayedChannels = 0;
        // Built off the main thread, added in one go in onPostExecute: one post and one layout
        // pass for the page instead of one per channel.
        final List<BaseItemDto> rowChannels = new ArrayList<>();
        final List<LinearLayout> rows = new ArrayList<>();
        // This page's channel list: a Favorites / All switch mid-way swaps mAllChannels
        final List<BaseItemDto> channels = mAllChannels;

        @Override
        protected void onPreExecute() {
            Timber.d("*** Display programs pre-execute");
            mChannels.removeAllViews();
            mProgramRows.removeAllViews();
            displayedChannels = 0;
            addTopPagingRow();
        }

        @Override
        protected Void doInBackground(Integer... params) {
            int start = params[0];
            int end = params[1];
            boolean first = true;

            Timber.d("*** About to iterate programs");
            timing("rows: building");
            LinearLayout prevRow = null;
            for (int i = start; i <= end; i++) {
                if (isCancelled()) return null;
                if (i >= channels.size()) break;
                final BaseItemDto channel = channels.get(i);
                List<BaseItemDto> programs = TvManager.getProgramsForChannel(channel.getId(), mFilters);
                final LinearLayout row = getProgramRow(programs, channel.getId());
                if (row == null) continue; // no row to show

                if (first) {
                    first = false;
                    firstFocusView = row;
                }

                // set focus parameters if we are not on first row
                // this makes focus movements more predictable for the grid view
                if (prevRow != null) {
                    TvManager.setFocusParams(row, prevRow, true);
                    TvManager.setFocusParams(prevRow, row, false);
                }
                prevRow = row;

                rowChannels.add(channel);
                rows.add(row);

                displayedChannels++;
            }
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Timber.d("*** Display programs post execute");
            timing("rows built: " + rows.size());
            if (getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) {
                Context context = requireContext();
                for (int i = 0; i < rows.size(); i++) {
                    BaseItemDto channel = rowChannels.get(i);
                    LinearLayout row = rows.get(i);
                    GuideChannelHeader header = getChannelHeader(context, channel);
                    mChannels.addView(header);
                    header.loadImage();
                    mProgramRows.addView(row);
                    // put focus on the last tuned channel
                    if (channel.getId().equals(mFirstFocusChannelId)) {
                        firstFocusView = focusAtEnd ? row.getChildAt(row.getChildCount()-1) : row;
                        focusAtEnd = false;
                        mFirstFocusChannelId = null;
                    }
                }
            }

            addBottomPagingRow();

            updateChannelStatus(displayedChannels);
            mFilterStatus.setText(mFilters.toString() + " for "+getGuideHours()+" hours");
            mFilterStatus.setTextColor(mFilters.any() ? Color.WHITE : ContextCompat.getColor(requireContext(), R.color.guide_text_muted));

            mResetButton.setVisibility(mCurrentGuideStart.isAfter(LocalDateTime.now()) ? View.VISIBLE : View.GONE); // show reset button if paged ahead

            mSpinner.setVisibility(View.GONE);
            updateNowLine();
            if (firstFocusView != null && !mKeepHeaderFocus) {
                firstFocusView.requestFocus();
            }
            mKeepHeaderFocus = false;

            if (mLoadStartMs != 0) {
                timing("first screen, " + mView + ", " + displayedChannels + " rows" + (mPartialList ? " (first page)" : ""));
                // and when it is on screen: the next frame after this layout
                final long start = mLoadStartMs;
                mLoadStartMs = 0;
                mProgramRows.getViewTreeObserver().addOnDrawListener(new android.view.ViewTreeObserver.OnDrawListener() {
                    boolean logged = false;
                    @Override
                    public void onDraw() {
                        if (logged) return;
                        logged = true;
                        android.util.Log.i("GuideTiming", "first screen drawn +" + (android.os.SystemClock.uptimeMillis() - start) + " ms");
                        mProgramRows.post(() -> mProgramRows.getViewTreeObserver().removeOnDrawListener(this));
                    }
                });
            }

            // The first screen is up: now the rest of All, or a fresh read of the stored list
            if (mPartialList && !mRestLoading) loadRestOfChannels(mLoadGeneration);
            if (mRelistAfterPaint) {
                mRelistAfterPaint = false;
                if (mLiveUpdates != null) mLiveUpdates.relist();
            }
        }
    }

    /** The whole All list, read after its first page shows; the page's rows stay, paging reaches the rest. */
    private void loadRestOfChannels(final int generation) {
        mRestLoading = true;
        TvManagerHelperKt.loadAllGuideChannels(this, channels -> {
            if (!isAdded() || generation != mLoadGeneration || !mPartialList) return null;
            mRestLoading = false;
            if (channels == null) {
                // Try again in a while; the first page stays meanwhile
                mHandler.postDelayed(() -> {
                    if (isAdded() && generation == mLoadGeneration && mPartialList && !mRestLoading) loadRestOfChannels(generation);
                }, 5_000);
                return null;
            }
            onRestOfChannels(TvManager.setLiveChannels(false, channels));
            return null;
        });
    }

    /**
     * Guide load milestones in logcat (tag `GuideTiming`, ms since the load began), in every build:
     * android.util.Log, as the release build's Timber drops info lines. `adb logcat -s GuideTiming`.
     */
    private void timing(String what) {
        if (mLoadStartMs == 0) return;
        android.util.Log.i("GuideTiming", what + " +" + (android.os.SystemClock.uptimeMillis() - mLoadStartMs) + " ms");
    }

    private void onRestOfChannels(List<BaseItemDto> all) {
        android.util.Log.i("GuideTiming", "whole All list in, " + all.size() + " channels");
        List<BaseItemDto> page = mAllChannels;
        mPartialList = false;
        mAllCount = all.size();
        mFavoriteCount = countFavorites(all);
        mAllChannels = all;
        updateToggle();
        if (all.isEmpty()) {
            showNoChannels(false);
            return;
        }
        boolean busy = mProgramsLoading || (mDisplayProgramsTask != null && mDisplayProgramsTask.getStatus() != AsyncTask.Status.FINISHED);
        if (!busy && GuideModel.isFirstPageOf(idsOf(page), idsOf(all))) {
            // The rows shown are the list's first rows: only the paging row for the rest comes
            mCurrentDisplayChannelEndNdx = Math.min(mCurrentDisplayChannelEndNdx, all.size() - 1);
            addBottomPagingRow();
            updateChannelStatus(shownChannelIds().size());
        } else {
            // The channels changed between the two reads: this page anew from the whole list
            displayChannels(0, pageSize);
        }
    }

    /** "Showing 75 of 1,700 channels". */
    private void updateChannelStatus(int displayed) {
        java.text.NumberFormat numbers = java.text.NumberFormat.getIntegerInstance();
        int total = mPartialList && mAllCount >= 0 ? mAllCount : mAllChannels.size();
        mChannelStatus.setText(getString(R.string.msg_guide_channels_count, numbers.format(displayed), numbers.format(total)));
    }

    /** The paging row for the channels above this page, first in both columns (none on the first page). */
    private void addTopPagingRow() {
        if (mCurrentDisplayChannelStartNdx <= 0 || mAllChannels == null || mCurrentDisplayChannelStartNdx > mAllChannels.size()) return;
        int pageUpStart = Math.max(0, mCurrentDisplayChannelStartNdx - pageSize);

        TextView placeHolder = new TextView(requireContext());
        placeHolder.setHeight(guideRowHeightPx);
        mChannels.addView(placeHolder, 0);

        String label = TextUtilsKt.getLoadChannelsLabel(requireContext(), mAllChannels.get(pageUpStart).getNumber(), mAllChannels.get(mCurrentDisplayChannelStartNdx - 1).getNumber());
        mProgramRows.addView(new GuidePagingButton(requireContext(), LiveTvGuideFragment.this, pageUpStart, label), 0);
    }

    /** The paging row for the channels below this page, last in both columns (none on the last page or with filters). */
    private void addBottomPagingRow() {
        if (mAllChannels == null || mCurrentDisplayChannelEndNdx >= mAllChannels.size() - 1 || mFilters.any()) return;
        int pageDnEnd = Math.min(mCurrentDisplayChannelEndNdx + pageSize, mAllChannels.size() - 1);

        TextView placeHolder = new TextView(requireContext());
        placeHolder.setHeight(guideRowHeightPx);
        mChannels.addView(placeHolder);

        String label = TextUtilsKt.getLoadChannelsLabel(requireContext(), mAllChannels.get(mCurrentDisplayChannelEndNdx + 1).getNumber(), mAllChannels.get(pageDnEnd).getNumber());
        mProgramRows.addView(new GuidePagingButton(requireContext(), LiveTvGuideFragment.this, mCurrentDisplayChannelEndNdx + 1, label));
    }

    // Live updates (GuideLiveUpdates): favourite and lineup changes applied to the rows in place.

    GuideView getGuideView() { return mView; }
    int getLoadGeneration() { return mLoadGeneration; }
    @Nullable List<BaseItemDto> getLiveChannels() { return mAllChannels; }

    /** The guide shows its channels and isn't loading: a live change may edit the rows. */
    boolean isLiveReady() {
        return isAdded() && getView() != null && mView != null && mAllChannels != null && !mPartialList
                && mSpinner.getVisibility() != View.VISIBLE
                && !mProgramsLoading
                && (mDisplayProgramsTask == null || mDisplayProgramsTask.getStatus() == AsyncTask.Status.FINISHED);
    }

    /** The channel ids of the rows shown, top to bottom. */
    private List<UUID> shownChannelIds() {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < mChannels.getChildCount(); i++) {
            View child = mChannels.getChildAt(i);
            if (child instanceof GuideChannelHeader) ids.add(((GuideChannelHeader) child).getChannel().getId());
        }
        return ids;
    }

    private static List<UUID> idsOf(List<BaseItemDto> channels) {
        List<UUID> ids = new ArrayList<>(channels.size());
        for (BaseItemDto channel : channels) ids.add(channel.getId());
        return ids;
    }

    /** The rows of [target] to show after a live change (indexes); null: show a page anew. */
    @Nullable
    private kotlin.ranges.IntRange liveWindow(List<BaseItemDto> target) {
        return GuideModel.guideLiveWindow(idsOf(mAllChannels), mCurrentDisplayChannelStartNdx, mCurrentDisplayChannelEndNdx, shownChannelIds(), idsOf(target));
    }

    /** The channels that get a new row when [target] is applied: their programmes are needed first. */
    List<UUID> rowsNeededFor(List<BaseItemDto> target) {
        List<UUID> needed = new ArrayList<>();
        if (mAllChannels == null) return needed;
        kotlin.ranges.IntRange window = liveWindow(target);
        if (window == null) return needed;
        java.util.Set<UUID> shown = new java.util.HashSet<>(shownChannelIds());
        java.util.Set<UUID> old = new java.util.HashSet<>(idsOf(mAllChannels));
        for (int i = window.getFirst(); i <= window.getLast(); i++) {
            UUID id = target.get(i).getId();
            // With filters a channel already in the list and not shown has nothing that passes
            if (!shown.contains(id) && (!mFilters.any() || !old.contains(id))) needed.add(id);
        }
        return needed;
    }

    /** Only the counts changed (the list is the same): the toggle shows them. */
    void onLiveCounts(@Nullable Integer allCount) {
        if (allCount != null && mView == GuideView.FAVORITES && allCount != mAllCount) {
            mAllCount = allCount;
            updateToggle();
        }
    }

    /** The view's guide data now ends at [end] (its channels changed): the dates follow, when they differ. */
    void onLiveGuideEnd(LocalDateTime end) {
        if (end.equals(mFurthestEnd)) return;
        mFurthestEnd = end;
        List<LocalDate> days = GuideModel.guideDays(LocalDateTime.now(), mFurthestEnd);
        boolean show = days.size() >= 2 && mAllChannels != null && !mAllChannels.isEmpty();
        if (days.equals(mGuideDays) && show == (mDays.getVisibility() == View.VISIBLE)) return;
        View focus = requireActivity().getCurrentFocus();
        int focusedDay = focus != null && focus.getParent() == mDays ? mDays.indexOfChild(focus) : -1;
        LocalDate day = focusedDay >= 0 && focusedDay < mGuideDays.size() ? mGuideDays.get(focusedDay) : null;
        updateDays();
        if (day != null) {
            int at = mGuideDays.indexOf(day);
            View chip = mDays.getChildAt(at >= 0 ? at : Math.min(focusedDay, mDays.getChildCount() - 1));
            if (chip != null) chip.requestFocus();
            else mViewFavorites.requestFocus();
        }
    }

    /** The cell of [row] at [time] (the one on then), else its first. */
    private static View cellAt(LinearLayout row, @Nullable LocalDateTime time) {
        if (time != null) {
            for (int i = 0; i < row.getChildCount(); i++) {
                View cell = row.getChildAt(i);
                if (cell instanceof ProgramGridCell) {
                    BaseItemDto program = ((ProgramGridCell) cell).getProgram();
                    if (program != null && program.getEndDate() != null && program.getEndDate().isAfter(time)) return cell;
                }
            }
        }
        return row.getChildCount() > 0 ? row.getChildAt(0) : row;
    }

    /** Puts [view] at [index] of [parent], moving it only when it isn't there already. */
    private static void placeAt(LinearLayout parent, View view, int index) {
        if (parent.getChildAt(index) == view) return;
        if (view.getParent() == parent) parent.removeView(view);
        parent.addView(view, Math.min(index, parent.getChildCount()));
    }

    /** Up / down between programme rows after rows came or went: each cell to the one at its time. */
    private void relinkRows() {
        LinearLayout prev = null;
        for (int i = 0; i < mProgramRows.getChildCount(); i++) {
            View child = mProgramRows.getChildAt(i);
            if (!(child instanceof LinearLayout)) {
                prev = null;
                continue;
            }
            LinearLayout row = (LinearLayout) child;
            for (int c = 0; c < row.getChildCount(); c++) {
                row.getChildAt(c).setNextFocusUpId(View.NO_ID);
                row.getChildAt(c).setNextFocusDownId(View.NO_ID);
            }
            if (prev != null) {
                TvManager.setFocusParams(row, prev, true);
                TvManager.setFocusParams(prev, row, false);
            }
            prev = row;
        }
    }

    /**
     * Shows [target] instead of the guide's channels without a reload: rows of channels that went are
     * removed, new ones added (their programmes are already in TvManager), changed ones redrawn, and
     * the rest moved only when the order changed. Focus stays on its row and programme (the nearest
     * row when its row went) and the rows on screen stay where they were.
     */
    void applyLiveChannels(List<BaseItemDto> target, @Nullable Integer allCount) {
        if (!isLiveReady()) return;
        boolean favorites = mView == GuideView.FAVORITES;
        List<BaseItemDto> old = mAllChannels;
        kotlin.ranges.IntRange window = liveWindow(target);
        View focus = requireActivity().getCurrentFocus();

        List<BaseItemDto> stored = TvManager.setLiveChannels(favorites, target);
        if (favorites) {
            mFavoriteCount = stored.size();
            if (allCount != null) mAllCount = allCount;
        } else {
            mAllCount = stored.size();
            mFavoriteCount = countFavorites(stored);
        }

        if (stored.isEmpty()) {
            boolean inGrid = focus == null || isInGrid(focus) || !focus.isShown();
            mAllChannels = stored;
            showNoChannels(inGrid);
            updateToggle();
            updateDays();
            return;
        }

        if (window == null) {
            // None of the rows shown is left (or none was shown, as in empty Favorites): this page anew
            boolean inGrid = focus == null || isInGrid(focus) || focus == mShowAllButton;
            mAllChannels = stored;
            mEmptyFavorites.setVisibility(View.GONE);
            mKeepHeaderFocus = !inGrid;
            mFirstFocusChannelId = TvManager.getLastLiveTvChannel();
            updateToggle();
            updateDays();
            if (inGrid) mViewFavorites.requestFocus();
            int start = Math.max(0, Math.min(mCurrentDisplayChannelStartNdx, stored.size() - 1));
            displayChannels(mFilters.any() ? 0 : start, PAGE_SIZE);
            return;
        }

        // The rows shown now, and where focus and the screen are
        java.util.Map<UUID, View[]> rows = new java.util.HashMap<>();
        List<UUID> order = new ArrayList<>();
        UUID focusedId = null;
        boolean focusOnHeader = false;
        LocalDateTime focusTime = null;
        for (int i = 0; i < mChannels.getChildCount() && i < mProgramRows.getChildCount(); i++) {
            View header = mChannels.getChildAt(i);
            if (!(header instanceof GuideChannelHeader)) continue;
            View row = mProgramRows.getChildAt(i);
            UUID id = ((GuideChannelHeader) header).getChannel().getId();
            rows.put(id, new View[]{header, row});
            order.add(id);
            if (focus == header) {
                focusedId = id;
                focusOnHeader = true;
            } else if (focus != null && focus.getParent() == row) {
                focusedId = id;
                if (focus instanceof ProgramGridCell && ((ProgramGridCell) focus).getProgram() != null)
                    focusTime = ((ProgramGridCell) focus).getProgram().getStartDate();
            }
        }
        int scrollY = mProgramVScroller.getScrollY();
        UUID anchorId = focusedId;
        if (anchorId == null) {
            for (UUID id : order) {
                if (rows.get(id)[1].getBottom() > scrollY) {
                    anchorId = id;
                    break;
                }
            }
        }
        int anchorOffset = anchorId != null ? rows.get(anchorId)[1].getTop() - scrollY : 0;

        // The rows to show: kept ones updated, new ones built
        java.util.Set<UUID> oldIds = new java.util.HashSet<>(idsOf(old));
        List<UUID> desired = new ArrayList<>();
        java.util.Map<UUID, View[]> fresh = new java.util.HashMap<>();
        for (int i = window.getFirst(); i <= window.getLast(); i++) {
            BaseItemDto channel = stored.get(i);
            View[] pair = rows.get(channel.getId());
            if (pair != null) {
                ((GuideChannelHeader) pair[0]).update(channel);
                desired.add(channel.getId());
            } else if (!mFilters.any() || !oldIds.contains(channel.getId())) {
                LinearLayout row = getProgramRow(TvManager.getProgramsForChannel(channel.getId(), mFilters), channel.getId());
                if (row == null) continue; // nothing that passes the filters
                fresh.put(channel.getId(), new View[]{getChannelHeader(requireContext(), channel), row});
                desired.add(channel.getId());
            }
        }
        java.util.Set<UUID> desiredSet = new java.util.HashSet<>(desired);
        if (desired.isEmpty()) {
            // Only filtered-out channels are left in this page
            mAllChannels = stored;
            showNoChannels(false);
            updateToggle();
            return;
        }

        // Focus first moves off a row that goes, so it never falls anywhere else
        View keepFocus = focusedId != null ? focus : null;
        if (focusedId != null && !desiredSet.contains(focusedId)) {
            UUID next = GuideModel.nearestKeptRow(order, focusedId, desiredSet);
            if (next == null) next = desired.get(0);
            View[] pair = rows.containsKey(next) ? rows.get(next) : fresh.get(next);
            keepFocus = focusOnHeader ? pair[0] : cellAt((LinearLayout) pair[1], focusTime);
            dismissProgramOptions();
            if (keepFocus.isAttachedToWindow()) keepFocus.requestFocus();
            anchorId = next;
        } else if (anchorId != null && !desiredSet.contains(anchorId)) {
            anchorId = GuideModel.nearestKeptRow(order, anchorId, desiredSet);
        }

        // Paging rows go (they come back for the new page), then the rows that went
        for (int i = mChannels.getChildCount() - 1; i >= 0; i--) {
            if (!(mChannels.getChildAt(i) instanceof GuideChannelHeader)) {
                mChannels.removeViewAt(i);
                if (i < mProgramRows.getChildCount()) mProgramRows.removeViewAt(i);
            }
        }
        for (UUID id : order) {
            if (desiredSet.contains(id)) continue;
            View[] pair = rows.get(id);
            mChannels.removeView(pair[0]);
            mProgramRows.removeView(pair[1]);
        }
        // The rest in the new order; a view is moved only when out of place
        for (int i = 0; i < desired.size(); i++) {
            UUID id = desired.get(i);
            View[] pair = rows.containsKey(id) ? rows.get(id) : fresh.get(id);
            placeAt(mChannels, pair[0], i);
            placeAt(mProgramRows, pair[1], i);
            if (fresh.containsKey(id)) ((GuideChannelHeader) pair[0]).loadImage();
        }

        mAllChannels = stored;
        if (mFilters.any()) {
            mCurrentDisplayChannelStartNdx = 0;
            mCurrentDisplayChannelEndNdx = stored.size() - 1;
        } else {
            mCurrentDisplayChannelStartNdx = window.getFirst();
            mCurrentDisplayChannelEndNdx = window.getLast();
        }
        addTopPagingRow();
        addBottomPagingRow();
        relinkRows();
        updateChannelStatus(desired.size());
        mEmptyFavorites.setVisibility(View.GONE);
        updateToggle();
        updateNowLine();
        if (keepFocus != null && !keepFocus.hasFocus() && keepFocus.isAttachedToWindow()) keepFocus.requestFocus();

        // The screen stays put: the anchor row (focused, else the first in view) keeps its place
        if (anchorId != null) {
            View[] anchor = rows.containsKey(anchorId) ? rows.get(anchorId) : fresh.get(anchorId);
            final View anchorRow = anchor != null ? anchor[1] : null;
            final int offset = anchorOffset;
            if (anchorRow != null) {
                mProgramVScroller.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
                    @Override
                    public void onGlobalLayout() {
                        mProgramVScroller.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        if (anchorRow.getParent() == mProgramRows) mProgramVScroller.scrollTo(0, Math.max(0, anchorRow.getTop() - offset));
                    }
                });
                mProgramRows.requestLayout();
            }
        }
    }

    private boolean isInGrid(View view) {
        for (android.view.ViewParent p = view.getParent(); p != null; p = p.getParent()) {
            if (p == mChannels || p == mProgramRows) return true;
        }
        return false;
    }

    private int currentCellId = 0;

    private GuideChannelHeader getChannelHeader(Context context, org.jellyfin.sdk.model.api.BaseItemDto channel){
        return new GuideChannelHeader(context, this, channel);
    }

    private LinearLayout getProgramRow(List<BaseItemDto> programs, UUID channelId) {

        LinearLayout programRow = new LinearLayout(requireContext());

        if (programs.size() == 0) {
            if (mFilters.any()) return null; // don't show rows with no program data

            int minutes = ((Long) ((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - mCurrentGuideStart.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue();
            int slot = 0;
            do {
                BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                        getContext(),
                        channelId,
                        mCurrentGuideStart.plusMinutes(30l * slot),
                        mCurrentGuideEnd.plusMinutes(30l * (slot + 1))
                );

                ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
                cell.setId(currentCellId++);
                cell.setLayoutParams(new ViewGroup.LayoutParams(30 * guideRowWidthPerMinPx, guideRowHeightPx));
                programRow.addView(cell);
                if (slot == 0)
                    cell.setFirst();
                if (slot == (minutes / 30) - 1)
                    cell.setLast();
                slot++;
            } while((30*slot) < minutes);
            return programRow;
        }

        LocalDateTime prevEnd = getCurrentLocalStartDate();
        for (BaseItemDto item : programs) {
            LocalDateTime start = item.getStartDate() != null ? item.getStartDate() : getCurrentLocalStartDate();
            if (start.isBefore(getCurrentLocalStartDate())) {
                start = getCurrentLocalStartDate();
            }

            if (start.isBefore(prevEnd))
                continue;

            if (start.isAfter(prevEnd)) {
                // fill empty time slot
                BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                        getContext(),
                        channelId,
                        prevEnd,
                        start
                );

                ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
                cell.setId(currentCellId++);
                cell.setLayoutParams(new ViewGroup.LayoutParams(((Long) ((start.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
                if (prevEnd == mCurrentGuideStart) {
                    cell.setFirst();
                }
                programRow.addView(cell);
            }
            LocalDateTime end = item.getEndDate() != null ? item.getEndDate() : getCurrentLocalEndDate();
            if (end.isAfter(getCurrentLocalEndDate())) end = getCurrentLocalEndDate();
            prevEnd = end;
            Long duration = (end.toInstant(ZoneOffset.UTC).toEpochMilli() - start.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000;
            if (duration > 0) {
                ProgramGridCell program = new ProgramGridCell(requireContext(), this, item, false);
                program.setId(currentCellId++);
                program.setLayoutParams(new ViewGroup.LayoutParams(duration.intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
                if (start == mCurrentGuideStart) {
                    program.setFirst();
                }
                if (end == mCurrentGuideEnd) {
                    program.setLast();
                }

                programRow.addView(program);
            }
        }

        //If not at end of time period - fill in the rest
        if (prevEnd.isBefore(mCurrentGuideEnd)) {
            // fill empty time slot
            BaseItemDto empty = LiveTvGuideFragmentHelperKt.createNoProgramDataBaseItem(
                    getContext(),
                    channelId,
                    prevEnd,
                    mCurrentGuideEnd
            );

            ProgramGridCell cell = new ProgramGridCell(requireContext(), this, empty, false);
            cell.setId(currentCellId++);
            cell.setLayoutParams(new ViewGroup.LayoutParams(((Long)((mCurrentGuideEnd.toInstant(ZoneOffset.UTC).toEpochMilli() - prevEnd.toInstant(ZoneOffset.UTC).toEpochMilli()) / 60000)).intValue() * guideRowWidthPerMinPx, guideRowHeightPx));
            programRow.addView(cell);
        }

        return programRow;
    }

    private void fillTimeLine(LocalDateTime start, int hours) {
        mCurrentGuideStart = start;
        mCurrentGuideStart = mCurrentGuideStart
                .withMinute(mCurrentGuideStart.getMinute())
                .withSecond(0)
                .withNano(0);

        mDisplayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mCurrentGuideStart));
        mCurrentGuideEnd = mCurrentGuideStart
                .plusHours(hours);
        int oneHour = 60 * guideRowWidthPerMinPx;
        int halfHour = 30 * guideRowWidthPerMinPx;

        int interval = mCurrentGuideStart.getMinute() >= 30 ? 60 - mCurrentGuideStart.getMinute() : 30 - mCurrentGuideStart.getMinute();
        mTimeline.removeAllViews();

        LocalDateTime current = mCurrentGuideStart;
        while (current.isBefore(mCurrentGuideEnd)) {
            TextView time = new TextView(requireContext());
            time.setText(DateTimeExtensionsKt.getTimeFormatter(getContext()).format(current));
            time.setTextSize(13);
            time.setTextColor(ContextCompat.getColor(requireContext(), R.color.guide_text_muted));
            time.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            time.setPadding(Utils.convertDpToPixel(requireContext(), 8), 0, 0, 0);
            time.setSingleLine(true);
            time.setWidth(interval != 60 ? ( interval < 15 ? 15 * guideRowWidthPerMinPx : interval * guideRowWidthPerMinPx) : oneHour);
            mTimeline.addView(time);
            current = current.plusMinutes(interval);
            //after first one, we always go on hours
            interval = interval < 30 ? 30 : 60;
        }
        updateDaySelection();
        updateNowLine();
    }

    /** The "now" line through the grid and its time on the timeline; hidden when now isn't in the hours shown. */
    private void updateNowLine() {
        if (mNowLine == null || mCurrentGuideEnd == null) return;
        LocalDateTime now = LocalDateTime.now();
        if (now.isBefore(mCurrentGuideStart) || !now.isBefore(mCurrentGuideEnd) || mProgramRows.getChildCount() == 0) {
            mNowLine.setVisibility(View.GONE);
            mNowPill.setVisibility(View.GONE);
            return;
        }
        final float x = java.time.Duration.between(mCurrentGuideStart, now).getSeconds() / 60f * guideRowWidthPerMinPx;
        mNowLine.setTranslationX(x);
        mNowLine.setVisibility(View.VISIBLE);
        mNowPill.setText(DateTimeExtensionsKt.getTimeFormatter(requireContext()).format(now));
        mNowPill.setVisibility(View.VISIBLE);
        mNowPill.post(() -> {
            float left = Math.max(0f, x - mNowPill.getWidth() / 2f);
            mNowPill.setTranslationX(left);
            // A half-hour label the pill covers steps aside, so the two never print over each other
            float gap = Utils.convertDpToPixel(requireContext(), 4);
            for (int i = 0; i < mTimeline.getChildCount(); i++) {
                if (!(mTimeline.getChildAt(i) instanceof TextView)) continue;
                TextView label = (TextView) mTimeline.getChildAt(i);
                float start = label.getLeft() + label.getPaddingLeft();
                float end = start + label.getPaint().measureText(label.getText().toString());
                label.setAlpha(end + gap > left && start - gap < left + mNowPill.getWidth() ? 0f : 1f);
            }
        });
    }

    /** Every minute: the now line moves, and programmes that started or ended change highlight. */
    private final Runnable mNowTick = new Runnable() {
        @Override
        public void run() {
            if (!isAdded()) return;
            updateNowLine();
            for (int r = 0; r < mProgramRows.getChildCount(); r++) {
                View row = mProgramRows.getChildAt(r);
                if (!(row instanceof LinearLayout)) continue;
                LinearLayout cells = (LinearLayout) row;
                for (int c = 0; c < cells.getChildCount(); c++) {
                    View cell = cells.getChildAt(c);
                    if (cell instanceof ProgramGridCell) ((ProgramGridCell) cell).refreshNow();
                }
            }
            mHandler.postDelayed(this, 60_000);
        }
    };

    public LocalDateTime getCurrentLocalStartDate() { return mCurrentGuideStart; }
    public LocalDateTime getCurrentLocalEndDate() { return mCurrentGuideEnd; }

    private Runnable detailUpdateTask = new Runnable() {
        @Override
        public void run() {
            if (!getLifecycle().getCurrentState().isAtLeast(Lifecycle.State.STARTED)) return;
            LiveTvGuideFragmentHelperKt.refreshSelectedProgram(LiveTvGuideFragment.this);
        }
    };

    void detailUpdateInternal() {
        if (mSelectedProgram == null) return;

        mTitle.setText(mSelectedProgram.getName());
        mSummary.setText(mSelectedProgram.getOverview());
        boolean noData = mSelectedProgram.getType() == org.jellyfin.sdk.model.api.BaseItemKind.FOLDER;
        if (!noData && mSelectedProgram.getStartDate() != null && mSelectedProgram.getEndDate() != null) {
            String range = GuideModel.formatTimeRange(mSelectedProgram.getStartDate(), mSelectedProgram.getEndDate(), DateTimeExtensionsKt.getTimeFormatter(requireContext()));
            String episode = GuideModel.episodeText(mSelectedProgram.getParentIndexNumber(), mSelectedProgram.getIndexNumber(), mSelectedProgram.getEpisodeTitle(),
                    n -> getString(R.string.lbl_season_number, n), n -> getString(R.string.lbl_episode_number, n));
            mProgramTime.setText(GuideModel.detailLine(range, mSelectedProgram.getChannelName(), episode));
        } else {
            mProgramTime.setText("");
        }

        // Info row: only the badges (New, Repeat, Live, rating); time, channel and episode are on the line above
        InfoLayoutHelper.addInfoRow(requireContext(), mSelectedProgram, mInfoRow, false);
        for (int i = 0; i < mInfoRow.getChildCount(); i++) {
            if (mInfoRow.getChildAt(i) instanceof BaseItemInfoRowView) ((BaseItemInfoRowView) mInfoRow.getChildAt(i)).setProgramSubText(false);
        }

        mDisplayDate.setText(TimeUtils.getFriendlyDate(requireContext(), mSelectedProgram.getStartDate()));
        String url = imageHelper.getValue().getPrimaryImageUrl(mSelectedProgram, null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT);
        mImage.load(url, null, ContextCompat.getDrawable(requireContext(), R.drawable.blank10x10), 0, 0);

        if (mDetailPopup != null && mDetailPopup.isShowing() && mSelectedProgramView != null) {
            mDetailPopup.setContent(mSelectedProgram, ((ProgramGridCell) mSelectedProgramView));
        }
    }

    public void setSelectedProgram(RelativeLayout programView) {
        mSelectedProgramView = programView;
        if (mSelectedProgramView instanceof ProgramGridCell) {
            mSelectedProgram = ((ProgramGridCell)mSelectedProgramView).getProgram();
            mHandler.removeCallbacks(detailUpdateTask);
            mHandler.postDelayed(detailUpdateTask, 500);
        } else if (mSelectedProgramView instanceof GuideChannelHeader) {
            for (int i = 0; i < mChannels.getChildCount(); i++) {
                if (mSelectedProgramView == mChannels.getChildAt(i)) {
                    LinearLayout programRow = (LinearLayout)mProgramRows.getChildAt(i);
                    if (programRow == null)
                        return;
                    for (int ii = 0; ii < programRow.getChildCount(); ii++) {
                        ProgramGridCell prog = (ProgramGridCell)programRow.getChildAt(ii);
                        if (prog.getProgram() != null && prog.getProgram().getStartDate().isBefore(LocalDateTime.now()) && prog.getProgram().getEndDate().isAfter(LocalDateTime.now())) {
                            mSelectedProgram = prog.getProgram();
                            if (mSelectedProgram != null) {
                                mHandler.removeCallbacks(detailUpdateTask);
                                mHandler.postDelayed(detailUpdateTask, 500);
                            }
                            return;
                        }
                    }
                }
            }
        }
    }
}
