package org.jellyfin.androidtv.ui;

import android.content.Context;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.AbsListView;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import org.jellyfin.androidtv.R;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuide;
import org.jellyfin.androidtv.ui.livetv.LiveTvGuideFragment;
import org.jellyfin.androidtv.util.ImageHelper;
import org.jellyfin.androidtv.util.Utils;
import org.jellyfin.sdk.model.api.BaseItemDto;
import org.koin.java.KoinJavaComponent;

public class GuideChannelHeader extends RelativeLayout {
    private AsyncImageView mChannelImage;
    private ImageView mFavImage;
    private BaseItemDto mChannel;
    private Context mContext;
    private LiveTvGuide mTvGuide;

    public GuideChannelHeader(Context context, LiveTvGuide tvGuide, BaseItemDto channel) {
        super(context);
        initComponent(context, tvGuide, channel);
    }

    private void initComponent(Context context, LiveTvGuide tvGuide, BaseItemDto channel) {
        mContext = context;
        mChannel = channel;
        mTvGuide = tvGuide;
        LayoutInflater inflater = LayoutInflater.from(context);
        View v = inflater.inflate(R.layout.channel_header, this, false);
        int headerWidth = Utils.convertDpToPixel(context, 160);
        v.setLayoutParams(new AbsListView.LayoutParams(
            headerWidth,
            Utils.convertDpToPixel(context, LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP)
        ));
        this.addView(v);
        // Exactly a programme row high, wherever it is added: the channel column and the programmes
        // line up at any depth
        setLayoutParams(new android.widget.LinearLayout.LayoutParams(
            LayoutParams.MATCH_PARENT,
            Utils.convertDpToPixel(context, LiveTvGuideFragment.GUIDE_ROW_HEIGHT_DP)
        ));
        this.setFocusable(true);
        setBackground(ContextCompat.getDrawable(mContext, R.drawable.guide_channel_bg));
        ((TextView) findViewById(R.id.channelName)).setText(channel.getName());
        ((TextView) findViewById(R.id.channelNumber)).setText(channel.getNumber());
        mChannelImage = findViewById(R.id.channelImage);
        mFavImage = findViewById(R.id.favImage);

        if (mChannel.getUserData() != null && mChannel.getUserData().isFavorite())
            mFavImage.setVisibility(View.VISIBLE);
    }

    public void loadImage() {
        ImageHelper imageHelper = KoinJavaComponent.<ImageHelper>get(ImageHelper.class);
        mChannelImage.load(
                imageHelper.getPrimaryImageUrl(mChannel,null, ImageHelper.MAX_PRIMARY_IMAGE_HEIGHT),
                null,
                null,
                0.0,
                0
        );
    }

    /** Its place in a multi-view pick, 1-4, as a mark on the logo; 0 hides it. */
    public void setPickNumber(int number) {
        TextView badge = findViewById(R.id.pickBadge);
        if (number > 0) {
            badge.setText(String.valueOf(number));
            badge.setVisibility(View.VISIBLE);
        } else {
            badge.setVisibility(View.GONE);
        }
    }

    public BaseItemDto getChannel() { return mChannel; }
    public void setChannel(BaseItemDto channel) { mChannel = channel; }

    /**
     * The guide's list changed live (another name, number, logo or star): this row shows [channel]
     * now, without a new view, so focus stays. The logo loads again only when it changed.
     */
    public void update(BaseItemDto channel) {
        boolean newImage = !java.util.Objects.equals(primaryTag(mChannel), primaryTag(channel));
        mChannel = channel;
        ((TextView) findViewById(R.id.channelName)).setText(channel.getName());
        ((TextView) findViewById(R.id.channelNumber)).setText(channel.getNumber());
        refreshFavorite();
        if (newImage) loadImage();
    }

    private static String primaryTag(BaseItemDto channel) {
        return channel.getImageTags() == null ? null : channel.getImageTags().get(org.jellyfin.sdk.model.api.ImageType.PRIMARY);
    }

    public void refreshFavorite() {
        if (mChannel.getUserData() != null && mChannel.getUserData().isFavorite())
            mFavImage.setVisibility(View.VISIBLE);
        else
            mFavImage.setVisibility(View.GONE);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);

        // On the purple focus the muted number would hardly show: white while focused
        ((TextView) findViewById(R.id.channelNumber)).setTextColor(ContextCompat.getColor(mContext, gainFocus ? R.color.white : R.color.guide_text_muted));
        if (gainFocus) {
            setBackground(ContextCompat.getDrawable(mContext, R.drawable.guide_channel_focused_bg));

            mTvGuide.setSelectedProgram(this);
        } else {
            setBackground(ContextCompat.getDrawable(mContext, R.drawable.guide_channel_bg));
        }
        // The focused background is an inset, and a View takes a background's insets as its padding
        // and keeps it when the next background has none: every channel focused once stayed 2dp
        // taller, so the column drifted below its programmes the further down one went.
        setPadding(0, 0, 0, 0);
    }
}
