/*
 * Copyright (C) 2006 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.app.ActivityManagerNative;
import android.content.ContentResolver;
import android.app.StatusBarManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.AlarmClock;
import android.provider.Settings;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.CharacterStyle;
import android.text.style.RelativeSizeSpan;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.View.OnLongClickListener;
import android.widget.TextView;

import com.android.systemui.DemoMode;

import com.android.internal.R;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import libcore.icu.LocaleData;

/**
 * Digital clock for the status bar.
 */
public class Clock extends TextView implements DemoMode, OnClickListener, OnLongClickListener {
    private boolean mAttached;
    private boolean mReceiverRegistered;
    private Calendar mCalendar;
    private String mClockFormatString;
    private SimpleDateFormat mClockFormat;
    private Locale mLocale;

    private static final int AM_PM_STYLE_NORMAL  = 0;
    private static final int AM_PM_STYLE_SMALL   = 1;
    private static final int AM_PM_STYLE_GONE    = 2;

    private static final int DOW_STYLE_NORMAL  = 0;
    private static final int DOW_STYLE_SMALL   = 1;
    private static final int DOW_STYLE_GONE    = 2;

    SettingsObserver observer = new SettingsObserver(new Handler());

    public Clock(Context context) {
        this(context, null);
    }

    public Clock(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public Clock(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        if (isClickable()) {
            setOnClickListener(this);
            setOnLongClickListener(this);
        }
    }

    private void updateReceiverState() {
        boolean shouldBeRegistered = mAttached && getVisibility() != GONE;
        if (shouldBeRegistered && !mReceiverRegistered) {
            IntentFilter filter = new IntentFilter();

            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_CONFIGURATION_CHANGED);
            filter.addAction(Intent.ACTION_USER_SWITCHED);

            getContext().registerReceiver(mIntentReceiver, filter, null, getHandler());
            observer.observe();
            mReceiverRegistered = true;
        } else if (!shouldBeRegistered && mReceiverRegistered) {
            getContext().unregisterReceiver(mIntentReceiver);
            observer.release();   
            mReceiverRegistered = false;
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();

        mAttached = true;
        updateReceiverState();

        // NOTE: It's safe to do these after registering the receiver since the receiver always runs
        // in the main thread, therefore the receiver can't run before this method returns.

        // The time zone may have changed while the receiver wasn't registered, so update the Time
        mCalendar = Calendar.getInstance(TimeZone.getDefault());

        // Make sure we update to the current time
        updateClock();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mAttached = false;
        updateReceiverState();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        boolean wasRegistered = mReceiverRegistered;
        updateReceiverState();
        if (!wasRegistered && mReceiverRegistered) {
            mCalendar = Calendar.getInstance(TimeZone.getDefault());
            updateClock();
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action.equals(Intent.ACTION_TIMEZONE_CHANGED)) {
                String tz = intent.getStringExtra("time-zone");
                mCalendar = Calendar.getInstance(TimeZone.getTimeZone(tz));
                if (mClockFormat != null) {
                    mClockFormat.setTimeZone(mCalendar.getTimeZone());
                }
            } else if (action.equals(Intent.ACTION_CONFIGURATION_CHANGED)) {
                final Locale newLocale = getResources().getConfiguration().locale;
                if (! newLocale.equals(mLocale)) {
                    mLocale = newLocale;
                    mClockFormatString = ""; // force refresh
                }
            }
            updateClock();
        }
    };

    final void updateClock() {
        Date now = new Date();
        Context context = getContext();
        Locale locale = Locale.getDefault();

        int textColor = Settings.System.getInt(context.getContentResolver(), Settings.System.STATUS_BAR_CLOCK_COLOR, 0xFFFFFFFF);
        CharSequence weekday = getDayOfWeek(now, locale);
        CharSequence smallTime = getSmallTime(now, locale); 
        CharSequence amPm = getAmPm(now, locale);    
        CharSequence time = TextUtils.concat(weekday, smallTime, amPm);
        
        setText(time);
        setTextColor(textColor);
    }

    private CharSequence getDayOfWeek(Date date, Locale locale){  
        Context context = getContext();
        int dowStyle = Settings.System.getInt(context.getContentResolver(), Settings.System.STATUS_BAR_DOW, 2); 

        if(dowStyle != DOW_STYLE_GONE){
            String day = new SimpleDateFormat("EEE", locale).format(date) + " ";
            if (dowStyle == DOW_STYLE_NORMAL){
                return day;
            } else {
                SpannableStringBuilder formatted = new SpannableStringBuilder(day);
                CharacterStyle style = new RelativeSizeSpan(0.7f);
                formatted.setSpan(style, 0, (day.length() - 1), Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                return formatted;
            }
        }
        return "";
    }

    private CharSequence getAmPm(Date date, Locale locale){
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context);
        int amPmStyle = Settings.System.getInt(context.getContentResolver(), Settings.System.STATUS_BAR_AM_PM, 2);

        if(is24){
            return "";
        } else if (amPmStyle != AM_PM_STYLE_GONE){
            String amPM = " " + new SimpleDateFormat("a", locale).format(date);
            if (amPmStyle == AM_PM_STYLE_NORMAL){
                return amPM;
            } else {
                SpannableStringBuilder formatted = new SpannableStringBuilder(amPM);
                CharacterStyle style = new RelativeSizeSpan(0.7f);
                formatted.setSpan(style, 0, 3, Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                return formatted;
            }
        }
        return "";
    }

    private CharSequence getSmallTime(Date date, Locale locale) {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context);
        String time;      
        
        if(is24){
            time = new SimpleDateFormat("HH:mm", locale).format(date);
        } else {
            time = new SimpleDateFormat("h:mm", locale).format(date);
        }     
        return time;
    }

    private final CharSequence getSmallTime() {
        Context context = getContext();
        boolean is24 = DateFormat.is24HourFormat(context);
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);
        int amPmStyle = Settings.System.getInt(context.getContentResolver(), Settings.System.STATUS_BAR_AM_PM, 2);

        final char MAGIC1 = '\uEF00';
        final char MAGIC2 = '\uEF01';

        SimpleDateFormat sdf;
        String format = is24 ? d.timeFormat24 : d.timeFormat12;
        if (!format.equals(mClockFormatString)) {
            /*
             * Search for an unquoted "a" in the format string, so we can
             * add dummy characters around it to let us find it again after
             * formatting and change its size.
             */
            if (amPmStyle != AM_PM_STYLE_NORMAL) {
                int a = -1;
                boolean quoted = false;
                for (int i = 0; i < format.length(); i++) {
                    char c = format.charAt(i);

                    if (c == '\'') {
                        quoted = !quoted;
                    }
                    if (!quoted && c == 'a') {
                        a = i;
                        break;
                    }
                }

                if (a >= 0) {
                    // Move a back so any whitespace before AM/PM is also in the alternate size.
                    final int b = a;
                    while (a > 0 && Character.isWhitespace(format.charAt(a-1))) {
                        a--;
                    }
                    format = format.substring(0, a) + MAGIC1 + format.substring(a, b)
                        + "a" + MAGIC2 + format.substring(b + 1);
                }
            }
            mClockFormat = sdf = new SimpleDateFormat(format);
            mClockFormatString = format;
        } else {
            sdf = mClockFormat;
        }
        String result = sdf.format(mCalendar.getTime());

        if (amPmStyle != AM_PM_STYLE_NORMAL) {
            int magic1 = result.indexOf(MAGIC1);
            int magic2 = result.indexOf(MAGIC2);
            if (magic1 >= 0 && magic2 > magic1) {
                SpannableStringBuilder formatted = new SpannableStringBuilder(result);
                if (amPmStyle == AM_PM_STYLE_GONE) {
                    formatted.delete(magic1, magic2+1);
                } else {
                    if (amPmStyle == AM_PM_STYLE_SMALL) {
                        CharacterStyle style = new RelativeSizeSpan(0.7f);
                        formatted.setSpan(style, magic1, magic2,
                                          Spannable.SPAN_EXCLUSIVE_INCLUSIVE);
                    }
                    formatted.delete(magic2, magic2 + 1);
                    formatted.delete(magic1, magic1 + 1);
                }
                return formatted;
            }
        }

        return result;

    }

    private void collapseStartActivity(Intent what) {
        // don't do anything if the activity can't be resolved (e.g. app disabled)
        if (getContext().getPackageManager().resolveActivity(what, 0) == null) {
            return;
        }

        // collapse status bar
        StatusBarManager statusBarManager = (StatusBarManager) getContext().getSystemService(
                Context.STATUS_BAR_SERVICE);
        statusBarManager.collapsePanels();

        // dismiss keyguard in case it was active and no passcode set
        try {
            ActivityManagerNative.getDefault().dismissKeyguardOnNextActivity();
        } catch (Exception ex) {
            // no action needed here
        }

        // start activity
        what.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        mContext.startActivity(what);
    }

    @Override
    public void onClick(View v) {
        Intent intent = new Intent(AlarmClock.ACTION_SHOW_ALARMS);
        collapseStartActivity(intent);
    }

    @Override
    public boolean onLongClick(View v) {
        Intent intent = new Intent("android.settings.DATE_SETTINGS");
        intent.addCategory(Intent.CATEGORY_DEFAULT);
        collapseStartActivity(intent);

        // consume event
        return true;
    }

    private boolean mDemoMode;

    @Override
    public void dispatchDemoCommand(String command, Bundle args) {
        if (!mDemoMode && command.equals(COMMAND_ENTER)) {
            mDemoMode = true;
        } else if (mDemoMode && command.equals(COMMAND_EXIT)) {
            mDemoMode = false;
            updateClock();
        } else if (mDemoMode && command.equals(COMMAND_CLOCK)) {
            String millis = args.getString("millis");
            String hhmm = args.getString("hhmm");
            if (millis != null) {
                mCalendar.setTimeInMillis(Long.parseLong(millis));
            } else if (hhmm != null && hhmm.length() == 4) {
                int hh = Integer.parseInt(hhmm.substring(0, 2));
                int mm = Integer.parseInt(hhmm.substring(2));
                mCalendar.set(Calendar.HOUR, hh);
                mCalendar.set(Calendar.MINUTE, mm);
            }
            setText(getSmallTime());
        }
    }

    class SettingsObserver extends ContentObserver {
        ContentResolver resolver;
        SettingsObserver(Handler handler) {
            super(handler);
            resolver = mContext.getContentResolver();
        }
 
        public void observe() {           
            Uri clockColourUri = Settings.System.getUriFor(Settings.System.STATUS_BAR_CLOCK_COLOR);
            Uri clockDowUri = Settings.System.getUriFor(Settings.System.STATUS_BAR_DOW);
            Uri clockAmPmUri = Settings.System.getUriFor(Settings.System.STATUS_BAR_AM_PM);
            resolver.registerContentObserver(clockColourUri, false, this);
            resolver.registerContentObserver(clockDowUri, false, this);
            resolver.registerContentObserver(clockAmPmUri, false, this);
        }
        
        public void release() {
            resolver.unregisterContentObserver(this);
        }
 
        @Override
        public void onChange(boolean selfChange) {
            updateClock();
        }
    }
}

