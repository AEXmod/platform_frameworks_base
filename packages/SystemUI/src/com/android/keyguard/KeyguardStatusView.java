/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.keyguard;

import android.app.ActivityManager;
import android.app.AlarmManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.support.v4.graphics.ColorUtils;
import android.database.ContentObserver;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.provider.Settings;
import android.service.dreams.DreamService;
import android.service.dreams.IDreamManager;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.Slog;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.ImageView;
import android.widget.TextClock;
import android.widget.TextView;
import android.os.RemoteException;
import android.os.ServiceManager;

import com.android.internal.util.ArrayUtils;
import com.android.internal.widget.LockPatternUtils;
//import com.android.systemui.ChargingView;
import com.android.systemui.doze.DozeLog;
import com.android.systemui.crdroid.omnijaws.OmniJawsClient;
import com.android.systemui.statusbar.policy.DateView;

import android.widget.CustomAnalogClock;
import android.widget.DeadPoolAnalogClock;
import android.widget.SpideyAnalogClock;
import android.widget.RelativeLayout;

import java.util.Locale;

public class KeyguardStatusView extends GridLayout implements
        OmniJawsClient.OmniJawsObserver {
    private static final boolean DEBUG = KeyguardConstants.DEBUG;
    private static final String TAG = "KeyguardStatusView";
    private static final int MARQUEE_DELAY_MS = 2000;
    private static final String FONT_FAMILY = "sans-serif-light";

    private final LockPatternUtils mLockPatternUtils;
    private final AlarmManager mAlarmManager;

    private TextView mAlarmStatusView;
    private DateView mDateView;
    private TextClock mClockView;
    private TextView mOwnerInfo;
    private ViewGroup mClockContainer;
    //private ChargingView mBatteryDoze;
    private View mKeyguardStatusArea;
    private Runnable mPendingMarqueeStart;
    private Handler mHandler;
    //On the first boot, keygard will start to receiver TIME_TICK intent.
    //And onScreenTurnedOff will not get called if power off when keyguard is not started.
    //Set initial value to false to skip the above case.
    private boolean mEnableRefresh = false;

    private View[] mVisibleInDoze;
    private boolean mPulsing;
    private float mDarkAmount = 0;
    private int mTextColor;
    private int mDateTextColor;
    private int mAlarmTextColor;
    private boolean mForcedMediaDoze;


    private View mWeatherView;
    private View weatherPanel;
    private TextView noWeatherInfo;
    private TextView mWeatherCity;
    private ImageView mWeatherConditionImage;
    private TextView mWeatherCurrentTemp;
    private TextView mWeatherConditionText;
    private OmniJawsClient mWeatherClient;
    private OmniJawsClient.WeatherInfo mWeatherData;

    private CustomAnalogClock mAnalogClockView;
    private DeadPoolAnalogClock mDeadPoolClockView;
    private SpideyAnalogClock mSpideyClockView;

    private boolean mShowWeather;
    private boolean mShowConditionIcon;
    private boolean mShowLocation;
    private boolean mShowAmbientWeather;
    private int mClockSelection;
    private int mDateSelection;

    private SettingsObserver mSettingsObserver;

    private KeyguardUpdateMonitorCallback mInfoCallback = new KeyguardUpdateMonitorCallback() {

        @Override
        public void onTimeChanged() {
            if (mEnableRefresh) {
                refresh();
            }
        }

        @Override
        public void onKeyguardVisibilityChanged(boolean showing) {
            if (showing) {
                if (DEBUG) Slog.v(TAG, "refresh statusview showing:" + showing);
                refresh();
                updateOwnerInfo();
            }
        }

        @Override
        public void onStartedWakingUp() {
            setEnableMarquee(true);
            mEnableRefresh = true;
            refresh();
        }

        @Override
        public void onFinishedGoingToSleep(int why) {
            setEnableMarquee(false);
            mEnableRefresh = false;
        }

        @Override
        public void onUserSwitchComplete(int userId) {
            refresh();
            updateOwnerInfo();
        }
    };

    public KeyguardStatusView(Context context) {
        this(context, null, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public KeyguardStatusView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        mAlarmManager = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
        mLockPatternUtils = new LockPatternUtils(getContext());
        mHandler = new Handler(Looper.myLooper());
        mWeatherClient = new OmniJawsClient(mContext);
    }

    private void setEnableMarquee(boolean enabled) {
        if (DEBUG) Log.v(TAG, "Schedule setEnableMarquee: " + (enabled ? "Enable" : "Disable"));
        if (enabled) {
            if (mPendingMarqueeStart == null) {
                mPendingMarqueeStart = () -> {
                    setEnableMarqueeImpl(true);
                    mPendingMarqueeStart = null;
                };
                mHandler.postDelayed(mPendingMarqueeStart, MARQUEE_DELAY_MS);
            }
        } else {
            if (mPendingMarqueeStart != null) {
                mHandler.removeCallbacks(mPendingMarqueeStart);
                mPendingMarqueeStart = null;
            }
            setEnableMarqueeImpl(false);
        }
    }

    private void setEnableMarqueeImpl(boolean enabled) {
        if (DEBUG) Log.v(TAG, (enabled ? "Enable" : "Disable") + " transport text marquee");
        if (mAlarmStatusView != null) mAlarmStatusView.setSelected(enabled);
        if (mOwnerInfo != null) mOwnerInfo.setSelected(enabled);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mClockContainer = findViewById(R.id.keyguard_clock_container);
        mAlarmStatusView = findViewById(R.id.alarm_status);
	mAnalogClockView = findViewById(R.id.analog_clock_view);
        mDeadPoolClockView = findViewById(R.id.deadpool_clock_view);
	mSpideyClockView = findViewById(R.id.spidey_clock_view);
        mDateView = findViewById(R.id.date_view);
        mClockView = findViewById(R.id.clock_view);
        mClockView.setShowCurrentUserTime(true);
        mOwnerInfo = findViewById(R.id.owner_info);
        //mBatteryDoze = findViewById(R.id.battery_doze);
        mKeyguardStatusArea = findViewById(R.id.keyguard_status_area);
        mVisibleInDoze = new View[]{/*mBatteryDoze, */mClockView, mKeyguardStatusArea};
        mTextColor = mClockView.getCurrentTextColor();
        mDateTextColor = mDateView.getCurrentTextColor();
        mAlarmTextColor = mAlarmStatusView.getCurrentTextColor();
        mWeatherView = findViewById(R.id.keyguard_weather_view);
        weatherPanel = findViewById(R.id.weather_panel);
        noWeatherInfo = (TextView) findViewById(R.id.no_weather_info_text);
        mWeatherCity = (TextView) findViewById(R.id.city);
        mWeatherConditionImage = (ImageView) findViewById(R.id.ls_weather_image);
        mWeatherCurrentTemp = (TextView) findViewById(R.id.current_temp);
        mWeatherConditionText = (TextView) findViewById(R.id.condition);

	mVisibleInDoze = new View[]{/*mBatteryDoze, */mClockView, mAnalogClockView, mDeadPoolClockView, mSpideyClockView, mKeyguardStatusArea};

        boolean shouldMarquee = KeyguardUpdateMonitor.getInstance(mContext).isDeviceInteractive();
        setEnableMarquee(shouldMarquee);
        refresh();
        updateOwnerInfo();

        mSettingsObserver = new SettingsObserver(new Handler());
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        Typeface tf = Typeface.create(FONT_FAMILY, Typeface.NORMAL);
        mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));
        mClockView.setTypeface(tf);
        MarginLayoutParams layoutParams = (MarginLayoutParams) mClockView.getLayoutParams();
        layoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_digital);
        mClockView.setLayoutParams(layoutParams);
	// Custom analog clock
        MarginLayoutParams customlayoutParams = (MarginLayoutParams) mAnalogClockView.getLayoutParams();
        customlayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_digital);
        mAnalogClockView.setLayoutParams(customlayoutParams);

        // DeadPool analog clock
        MarginLayoutParams deadpoollayoutParams = (MarginLayoutParams) mDeadPoolClockView.getLayoutParams();
        deadpoollayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_digital);
        mDeadPoolClockView.setLayoutParams(deadpoollayoutParams);

	// Spidey analog clock
        MarginLayoutParams spideylayoutParams = (MarginLayoutParams) mSpideyClockView.getLayoutParams();
        spideylayoutParams.bottomMargin = getResources().getDimensionPixelSize(
                R.dimen.bottom_text_spacing_digital);
        mSpideyClockView.setLayoutParams(spideylayoutParams);

        // DateView
        mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX, getResources().getDimensionPixelSize(
                mDateSelection == 0 ? R.dimen.widget_label_font_size : R.dimen.widget_label_custom_font_size));
        mDateView.setTypeface(tf);


        // OwnerInfo
        if (mOwnerInfo != null) {
            mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
           mOwnerInfo.setTypeface(tf);
        }

    }

    public void refreshTime() {
        mDateView.setDatePattern(Patterns.dateViewSkel);

	if (mClockSelection == 0) {
            mClockView.setFormat12Hour(Patterns.clockView12);
            mClockView.setFormat24Hour(Patterns.clockView24);
        } else if (mClockSelection == 1) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>h</strong>:mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong>:mm"));
        } else if (mClockSelection == 5) {
            mClockView.setFormat12Hour(Html.fromHtml("<strong>hh</strong><br>mm"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk</strong><br>mm"));
	} else if (mClockSelection == 6) {
	    mClockView.setFormat12Hour(Html.fromHtml("<strong>h:mm</strong>"));
            mClockView.setFormat24Hour(Html.fromHtml("<strong>kk:mm</strong>"));
        } else {
            mClockView.setFormat12Hour("hh\nmm");
            mClockView.setFormat24Hour("kk\nmm");
        }
    }

    private void refresh() {
        AlarmManager.AlarmClockInfo nextAlarm =
                mAlarmManager.getNextAlarmClock(UserHandle.USER_CURRENT);
        Patterns.update(mContext, nextAlarm != null);

        refreshTime();
        refreshAlarmStatus(nextAlarm);
    }

    void refreshAlarmStatus(AlarmManager.AlarmClockInfo nextAlarm) {
        if (nextAlarm != null) {
            String alarm = formatNextAlarm(mContext, nextAlarm);
            mAlarmStatusView.setText(alarm);
            mAlarmStatusView.setContentDescription(
                    getResources().getString(R.string.keyguard_accessibility_next_alarm, alarm));
            mAlarmStatusView.setVisibility(View.VISIBLE);
        } else {
            mAlarmStatusView.setVisibility(View.GONE);
        }
    }

    public int getClockBottom() {
        if (mShowWeather && mShowAmbientWeather && mWeatherClient.isOmniJawsEnabled()) {
            return mWeatherView.getBottom();
        } else {
            return mKeyguardStatusArea.getBottom();
        }
    }

    public float getClockTextSize() {
        return mClockView.getTextSize();
    }

    public static String formatNextAlarm(Context context, AlarmManager.AlarmClockInfo info) {
        if (info == null) {
            return "";
        }
        String skeleton = DateFormat.is24HourFormat(context, ActivityManager.getCurrentUser())
                ? "EHm"
                : "Ehma";
        String pattern = DateFormat.getBestDateTimePattern(Locale.getDefault(), skeleton);
        return DateFormat.format(pattern, info.getTriggerTime()).toString();
    }

    private void updateOwnerInfo() {
        if (mOwnerInfo == null) return;
        String ownerInfo = getOwnerInfo();
        if (!TextUtils.isEmpty(ownerInfo)) {
            mOwnerInfo.setVisibility(View.VISIBLE);
            mOwnerInfo.setText(ownerInfo);
        } else {
            mOwnerInfo.setVisibility(View.GONE);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        KeyguardUpdateMonitor.getInstance(mContext).registerCallback(mInfoCallback);
        mWeatherClient.addObserver(this);
        mSettingsObserver.observe();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        KeyguardUpdateMonitor.getInstance(mContext).removeCallback(mInfoCallback);
        mWeatherClient.removeObserver(this);
        mWeatherClient.cleanupObserver();
        mSettingsObserver.unobserve();
    }

    private String getOwnerInfo() {
        String info = null;
        if (mLockPatternUtils.isDeviceOwnerInfoEnabled()) {
            // Use the device owner information set by device policy client via
            // device policy manager.
            info = mLockPatternUtils.getDeviceOwnerInfo();
        } else {
            // Use the current user owner information if enabled.
            final boolean ownerInfoEnabled = mLockPatternUtils.isOwnerInfoEnabled(
                    KeyguardUpdateMonitor.getCurrentUser());
            if (ownerInfoEnabled) {
                info = mLockPatternUtils.getOwnerInfo(KeyguardUpdateMonitor.getCurrentUser());
            }
        }
        return info;
    }

    @Override
    public void weatherUpdated() {
        queryAndUpdateWeather();
    }

    public void queryAndUpdateWeather() {
        try {
            if (mWeatherClient.isOmniJawsEnabled() && mShowWeather) {
                mWeatherClient.queryWeather();
                mWeatherData = mWeatherClient.getWeatherInfo();
                if (mWeatherData == null) {
                    updateSettings();
                    return;
                }
                mWeatherCity.setText(mWeatherData.city);
                mWeatherConditionImage.setImageDrawable(
                    mWeatherClient.getWeatherConditionImage(mWeatherData.conditionCode));
                mWeatherCurrentTemp.setText(mWeatherData.temp + mWeatherData.tempUnits);
                mWeatherConditionText.setText(mWeatherData.condition);
            }
            updateSettings();
       } catch(Exception e) {
          // Do nothing
       }
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private boolean isDozeMode() {
        IDreamManager dreamManager = getDreamManager();
        try {
            if (dreamManager != null && dreamManager.isDozing()) {
                return true;
            }
        } catch (RemoteException e) {
            return false;
        }
        return false;
    }

    static IDreamManager getDreamManager() {
        return IDreamManager.Stub.asInterface(
                ServiceManager.checkService(DreamService.DREAM_SERVICE));
    }

    private void updateSettings() {
        boolean mWeatherEnabled = mWeatherClient.isOmniJawsEnabled();

        if (mWeatherView == null || weatherPanel == null)
            return;

        mWeatherView.setVisibility(mShowWeather && !isDozeMode() ? View.VISIBLE : View.GONE);

        if (noWeatherInfo != null) {
            noWeatherInfo.setVisibility(mShowWeather && !mWeatherClient.isOmniJawsEnabled() ?
                View.VISIBLE : View.GONE);
        }
        if (mWeatherEnabled && mShowWeather && mWeatherConditionText != null &&
                mWeatherConditionText.getText() != null) {
            mWeatherConditionText.setVisibility(View.VISIBLE);
        } else if (mWeatherConditionText != null) {
            mWeatherConditionText.setVisibility(View.GONE);
        }
        if (mWeatherEnabled && mShowWeather && mShowConditionIcon &&
                mWeatherConditionImage != null && mWeatherConditionImage.getDrawable() != null) {
            mWeatherConditionImage.setVisibility(View.VISIBLE);
        } else if (!mWeatherEnabled && mShowWeather && mShowConditionIcon &&
                mWeatherConditionImage != null) {
            mWeatherConditionImage.setImageDrawable(mContext
                .getResources().getDrawable(R.drawable.keyguard_weather_default_off));
            mWeatherConditionImage.setVisibility(View.VISIBLE);
        } else if (mWeatherConditionImage != null) {
            mWeatherConditionImage.setVisibility(View.GONE);
        }
        if (mWeatherEnabled && mShowWeather && mWeatherCurrentTemp != null &&
                mWeatherCurrentTemp.getText() != null) {
            mWeatherCurrentTemp.setVisibility(View.VISIBLE);
        } else if (mWeatherCurrentTemp != null) {
            mWeatherCurrentTemp.setVisibility(View.GONE);
        }
        if (mWeatherEnabled && mShowWeather && mShowLocation &&
                mWeatherCity != null && mWeatherCity.getText() != null) {
            mWeatherCity.setVisibility(View.VISIBLE);
        } else if (mWeatherCity != null) {
            mWeatherCity.setVisibility(View.GONE);
        }
        if (mWeatherConditionText.getVisibility() != View.VISIBLE &&
                mWeatherConditionImage.getVisibility() != View.VISIBLE &&
                mWeatherCurrentTemp.getVisibility() != View.VISIBLE) {
            weatherPanel.setVisibility(View.GONE);
            noWeatherInfo.setVisibility(mShowWeather ?
                View.VISIBLE : View.GONE);
        } else {
            weatherPanel.setVisibility(mShowWeather ?
                View.VISIBLE : View.GONE);
        }

	// Update clock and date styles
	RelativeLayout.LayoutParams params = (RelativeLayout.LayoutParams) mKeyguardStatusArea.getLayoutParams();

	if (mClockSelection == 6) {
	    // ClockView
	    mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_small_font_size));

	    // DateView
            mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
		getResources().getDimensionPixelSize(R.dimen.widget_label_small_font_size));

            // OwnerInfo
            if (mOwnerInfo != null) {
            	mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_small_font_size));
            }
	} else {
	    // ClockView
	    mClockView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_big_font_size));

	    // DateView
            mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));

            // OwnerInfo
            if (mOwnerInfo != null) {
            	mOwnerInfo.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
            }
	}

        switch (mClockSelection) {
            case 0: // default digital
            default:
                mClockView.setVisibility(View.VISIBLE);
                mAnalogClockView.setVisibility(View.GONE);
                mDeadPoolClockView.setVisibility(View.GONE);
		mSpideyClockView.setVisibility(View.GONE);
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(true);
                break;
            case 1: // digital (bold)
	    case 6: // digital (small)
                mClockView.setVisibility(View.VISIBLE);
                mAnalogClockView.setVisibility(View.GONE);
                mDeadPoolClockView.setVisibility(View.GONE);
		mSpideyClockView.setVisibility(View.GONE);
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
                mClockView.setSingleLine(true);
                break;
            case 2: // analog
	        mAnalogClockView.setVisibility(View.VISIBLE);
                mClockView.setVisibility(View.GONE);
                mDeadPoolClockView.setVisibility(View.GONE);
		mSpideyClockView.setVisibility(View.GONE);
		mWeatherView.setVisibility(View.GONE);
                params.addRule(RelativeLayout.BELOW, R.id.analog_clock_view);
                break;
            case 3: // analog (deadpool)
		mDeadPoolClockView.setVisibility(View.VISIBLE);
                mAnalogClockView.setVisibility(View.GONE);
                mClockView.setVisibility(View.GONE);
		mSpideyClockView.setVisibility(View.GONE);
		mWeatherView.setVisibility(View.GONE);
                params.addRule(RelativeLayout.BELOW, R.id.deadpool_clock_view);
                break;
            case 4: // sammy
            case 5: // sammy (bold)
                mClockView.setVisibility(View.VISIBLE);
                mAnalogClockView.setVisibility(View.GONE);
                mDeadPoolClockView.setVisibility(View.GONE);
		mSpideyClockView.setVisibility(View.GONE);
                params.addRule(RelativeLayout.BELOW, R.id.clock_view);
		mWeatherView.setVisibility(View.GONE);
                mClockView.setSingleLine(false);
                break;
	    case 7: //analog (spidey)
                mSpideyClockView.setVisibility(View.VISIBLE);
                mAnalogClockView.setVisibility(View.GONE);
                mClockView.setVisibility(View.GONE);
                mDeadPoolClockView.setVisibility(View.GONE);
		mWeatherView.setVisibility(View.GONE);
                params.addRule(RelativeLayout.BELOW, R.id.spidey_clock_view);
         }

        switch (mDateSelection) {
            case 0: // default aosp
            default:
                mDateView.setVisibility(View.VISIBLE);
                mDateView.setBackgroundResource(0);
                mDateView.setTypeface(Typeface.DEFAULT);
                mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
                mAlarmStatusView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_font_size));
                mDateView.setPadding(0,0,0,0);
                break;
            case 1: // default but bigger size
                mDateView.setBackgroundResource(0);
                mDateView.setTypeface(Typeface.DEFAULT);
                mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_custom_font_size));
                mAlarmStatusView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_custom_font_size));
                mDateView.setPadding(0,0,0,0);
                break;
            case 2: // semi-transparent box
                mDateView.setBackground(getResources().getDrawable(R.drawable.date_box_str_border));
                mDateView.setTypeface(Typeface.DEFAULT_BOLD);
                mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_custom_font_size));
                mAlarmStatusView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_custom_font_size));
                mDateView.setPadding(40,20,40,20);
                break;
            case 3: // semi-transparent box (round)
                mDateView.setBackground(getResources().getDrawable(R.drawable.date_str_border));
                mDateView.setTypeface(Typeface.DEFAULT_BOLD);
                mDateView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_custom_font_size));
                mAlarmStatusView.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                        getResources().getDimensionPixelSize(R.dimen.widget_label_custom_font_size));
                mDateView.setPadding(40,20,40,20);
                break;
        }

    }

    @Override
    public void weatherError(int errorReason) {
        if (DEBUG) Log.d(TAG, "weatherError " + errorReason);
        if (mShowWeather && !mWeatherClient.isOmniJawsEnabled()) {
            updateSettings();
        }
    }

    // DateFormat.getBestDateTimePattern is extremely expensive, and refresh is called often.
    // This is an optimization to ensure we only recompute the patterns when the inputs change.
    private static final class Patterns {
        static String dateViewSkel;
        static String clockView12;
        static String clockView24;
        static String cacheKey;

        static void update(Context context, boolean hasAlarm) {
            final Locale locale = Locale.getDefault();
            final Resources res = context.getResources();
            dateViewSkel = res.getString(hasAlarm
                    ? R.string.abbrev_wday_month_day_no_year_alarm
                    : R.string.abbrev_wday_month_day_no_year);
            final String clockView12Skel = res.getString(R.string.clock_12hr_format);
            final String clockView24Skel = res.getString(R.string.clock_24hr_format);
            final String key = locale.toString() + dateViewSkel + clockView12Skel + clockView24Skel;
            if (key.equals(cacheKey)) return;

            clockView12 = DateFormat.getBestDateTimePattern(locale, clockView12Skel);
            // CLDR insists on adding an AM/PM indicator even though it wasn't in the skeleton
            // format.  The following code removes the AM/PM indicator if we didn't want it.
            if (!clockView12Skel.contains("a")) {
                clockView12 = clockView12.replaceAll("a", "").trim();
            }

            clockView24 = DateFormat.getBestDateTimePattern(locale, clockView24Skel);

            cacheKey = key;
        }
    }

    public void setDark(float darkAmount) {
        if (mDarkAmount == darkAmount) {
            return;
        }
        mDarkAmount = darkAmount;

        boolean dark = darkAmount == 1;
        final int N = mClockContainer.getChildCount();
        for (int i = 0; i < N; i++) {
            View child = mClockContainer.getChildAt(i);
            if (!mForcedMediaDoze && ArrayUtils.contains(mVisibleInDoze, child)) {
                continue;
            }
            child.setAlpha(dark ? 0 : 1);
        }
        if (!mShowAmbientWeather || !mShowWeather || !mWeatherClient.isOmniJawsEnabled()) {
            mWeatherView.setAlpha(dark ? 0 : 1);
            weatherPanel.setAlpha(dark ? 0 : 1);
            noWeatherInfo.setAlpha(dark ? 0 : 1);
            mWeatherCity.setAlpha(dark ? 0 : 1);
            mWeatherConditionImage.setAlpha(dark ? 0 : 1);
            mWeatherCurrentTemp.setAlpha(dark ? 0 : 1);
            mWeatherConditionText.setAlpha(dark ? 0 : 1);
        }

        updateDozeVisibleViews();
        //mBatteryDoze.setDark(dark);
        mClockView.setTextColor(ColorUtils.blendARGB(mTextColor, Color.WHITE, darkAmount));
        mDateView.setTextColor(ColorUtils.blendARGB(mDateTextColor, Color.WHITE, darkAmount));
        int blendedAlarmColor = ColorUtils.blendARGB(mAlarmTextColor, Color.WHITE, darkAmount);
        mAlarmStatusView.setTextColor(blendedAlarmColor);
        mAlarmStatusView.setCompoundDrawableTintList(ColorStateList.valueOf(blendedAlarmColor));
	mAnalogClockView.setDark(dark);
        mDeadPoolClockView.setDark(dark);
        mSpideyClockView.setDark(dark);

    }

    public void setPulsing(boolean pulsing) {
        mPulsing = pulsing;
    }

    public void setCleanLayout(int reason) {
        mForcedMediaDoze =
                reason == DozeLog.PULSE_REASON_FORCED_MEDIA_NOTIFICATION;
        updateDozeVisibleViews();
    }

    private void updateDozeVisibleViews() {
        for (View child : mVisibleInDoze) {
            if (!mForcedMediaDoze) {
                child.setAlpha(mDarkAmount == 1 && mPulsing ? 0.8f : 1);
            } else {
                child.setAlpha(mDarkAmount == 1 ? 0 : 1);
            }
        }
	refreshTime();
    }

    class SettingsObserver extends ContentObserver {
        SettingsObserver(Handler handler) {
            super(handler);
        }

        void observe() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.registerContentObserver(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_SHOW_WEATHER), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.LOCK_SCREEN_AMBIENT_WEATHER), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.OMNIJAWS_WEATHER_ICON_PACK), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.LOCKSCREEN_CLOCK_SELECTION), false, this, UserHandle.USER_ALL);
            resolver.registerContentObserver(Settings.System.getUriFor(
                  Settings.System.LOCKSCREEN_DATE_SELECTION), false, this, UserHandle.USER_ALL);

            mShowWeather = Settings.System.getIntForUser(resolver,
                  Settings.System.LOCK_SCREEN_SHOW_WEATHER, 0, UserHandle.USER_CURRENT) == 1;
            mShowAmbientWeather = Settings.System.getIntForUser(resolver,
                  Settings.System.LOCK_SCREEN_AMBIENT_WEATHER, 0, UserHandle.USER_CURRENT) == 1;
            mShowConditionIcon = Settings.System.getIntForUser(resolver,
                  Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON, 1, UserHandle.USER_CURRENT) == 1;
            mShowLocation = Settings.System.getIntForUser(resolver,
                  Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION, 1, UserHandle.USER_CURRENT) == 1;
            
	    mClockSelection = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT);
	    mDateSelection = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_DATE_SELECTION, 0, UserHandle.USER_CURRENT);
	    queryAndUpdateWeather();
        }

        void unobserve() {
            ContentResolver resolver = mContext.getContentResolver();
            resolver.unregisterContentObserver(this);
        }

        @Override
        public void onChange(boolean selfChange, Uri uri) {
            ContentResolver resolver = mContext.getContentResolver();
            if (uri.equals(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_SHOW_WEATHER))) {
                mShowWeather = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCK_SCREEN_SHOW_WEATHER, 0, UserHandle.USER_CURRENT) == 1;
                queryAndUpdateWeather();
            } else if (uri.equals(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON))) {
                mShowConditionIcon = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCK_SCREEN_WEATHER_CONDITION_ICON, 1, UserHandle.USER_CURRENT) == 1;
                updateSettings();
            } else if (uri.equals(Settings.System.getUriFor(
                   Settings.System.LOCKSCREEN_CLOCK_SELECTION))) {
                mClockSelection = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_CLOCK_SELECTION, 0, UserHandle.USER_CURRENT);
                updateSettings();
            } else if (uri.equals(Settings.System.getUriFor(
                   Settings.System.LOCKSCREEN_DATE_SELECTION))) {
                mDateSelection = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCKSCREEN_DATE_SELECTION, 0, UserHandle.USER_CURRENT);
                updateSettings();
            } else if (uri.equals(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION))) {
                mShowLocation = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCK_SCREEN_SHOW_WEATHER_LOCATION, 1, UserHandle.USER_CURRENT) == 1;
            } else if (uri.equals(Settings.System.getUriFor(
                   Settings.System.LOCK_SCREEN_AMBIENT_WEATHER))) {
                mShowAmbientWeather = Settings.System.getIntForUser(resolver,
                    Settings.System.LOCK_SCREEN_AMBIENT_WEATHER, 0, UserHandle.USER_CURRENT) == 1;
            } else if (uri.equals(Settings.System.getUriFor(
                    Settings.System.OMNIJAWS_WEATHER_ICON_PACK))) {
                queryAndUpdateWeather();
            }
        }
    }
}
