package com.android.systemui.statusbar.policy;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.database.ContentObserver;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.TrafficStats;
import android.net.Uri;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.Html;
import android.provider.Settings;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import com.android.systemui.R;

import java.text.DecimalFormat;

/*
*
* Seeing how an Integer object in java requires at least 16 Bytes, it seemed awfully wasteful
* to only use it for a single boolean. 32-bits is plenty of room for what we need it to do.
*
*/
public class NetworkTraffic extends TextView {
    public static final int MASK_UP = 0x00000001;        // Least valuable bit
    public static final int MASK_DOWN = 0x00000002;      // Second least valuable bit
    public static final int MASK_UNIT = 0x00000004;      // Third least valuable bit
    public static final int MASK_PERIOD = 0xFFFF0000;    // Most valuable 16 bits

    private static final int KILOBIT = 1000;
    private static final int KILOBYTE = 1024;

    private static DecimalFormat decimalFormat = new DecimalFormat("##0.#");
    static {
        decimalFormat.setMaximumIntegerDigits(3);
        decimalFormat.setMaximumFractionDigits(1);
    }

    private int mState = 0;
    private int mInterval;
    private int mUnit;
    private int mColorUp;
    private int mColorDown;
    private int mColorIcon;
    private boolean mTextEnabled;
    private boolean mIconEnabled;
    private boolean mHideInactivity;
    private boolean mAttached;
    private boolean mShouldStart;
    private long totalRxBytes;
    private long totalTxBytes;
    private long lastUpdateTime;
    private int txtSizeSingle;
    private int txtSizeMulti;
    private int KB = KILOBIT;
    private int MB = KB * KB;
    private int GB = MB * KB;
        
    private ContentResolver resolver;
    
    private ContentObserver mSettingsObserver = new ContentObserver(new Handler()) {
        @Override
        public void onChange(boolean selfChange) {
            updateSettings();
        }
        
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            updateSettings();
        }
        
    };

    private Handler mTrafficHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            long timeDelta = SystemClock.elapsedRealtime() - lastUpdateTime;

            if (timeDelta < getInterval() * .95) {
                if (msg.what != 1) {
                    // we just updated the view, nothing further to do
                    return;
                }
                if (timeDelta < 1) {
                    // Can't div by 0 so make sure the value displayed is minimal
                    timeDelta = Long.MAX_VALUE;
                }
            }
            lastUpdateTime = SystemClock.elapsedRealtime();

            // Calculate the data rate from the change in total bytes and time
            long newTotalRxBytes = TrafficStats.getTotalRxBytes();
            long newTotalTxBytes = TrafficStats.getTotalTxBytes();
            long rxData = newTotalRxBytes - totalRxBytes;
            long txData = newTotalTxBytes - totalTxBytes;

            // If bit/s convert from Bytes to bits
            String symbol;
            if (KB == KILOBYTE) {
                symbol = "B/s";
            } else {
                symbol = "b/s";
                rxData = rxData * 8;
                txData = txData * 8;
            }

            // Get information for uplink ready so the line return can be added
            String output = "";
            if (isSet(mState, MASK_UP)) {

                String hexColor = String.format("#%06X", (0xFFFFFF & mColorUp));
                output = "<font color='" + hexColor + "'>";
                output += mTextEnabled ? formatOutput(timeDelta, txData, symbol) + " U" 
                        : formatOutput(timeDelta, txData, symbol); 
                output += "</font>";
            }

            // Ensure text size is where it needs to be
            int textSize;
            if (isSet(mState, MASK_UP + MASK_DOWN)) {
                output += "<br />";
                textSize = txtSizeMulti;
            } else {
                textSize = txtSizeSingle;
            }

            // Add information for downlink if it's called for
            if (isSet(mState, MASK_DOWN)) {
                String hexColor = String.format("#%06X", (0xFFFFFF & mColorDown));
                output += "<font color='" + hexColor + "'>";
                output += mTextEnabled ? formatOutput(timeDelta, rxData, symbol) + " D" 
                        : formatOutput(timeDelta, rxData, symbol); 
                output += "</font>";
            }
            setTextSize(TypedValue.COMPLEX_UNIT_PX, (float)textSize);
            
            if(mHideInactivity){
                if(getText().equals(output)){
                    setVisibility(View.GONE);
                } else {
                    setVisibility(View.VISIBLE);
                    setText(Html.fromHtml(output));
                }
            } else {
                setVisibility(View.VISIBLE);                
                setText(Html.fromHtml(output));
            }

            // Post delayed message to refresh in ~1000ms
            totalRxBytes = newTotalRxBytes;
            totalTxBytes = newTotalTxBytes;
            clearHandlerCallbacks();
            mTrafficHandler.postDelayed(mRunnable, getInterval());
        }

        private String formatOutput(long timeDelta, long data, String symbol) {
            long speed = (long)(data / (timeDelta / 1000F));
            if (speed < KB) {
                return decimalFormat.format(speed) + symbol;
            } else if (speed < MB) {
                return decimalFormat.format(speed / (float)KB) + 'k' + symbol;
            } else if (speed < GB) {
                return decimalFormat.format(speed / (float)MB) + 'M' + symbol;
            }
            return decimalFormat.format(speed / (float)GB) + 'G' + symbol;
        }
    };

    private Runnable mRunnable = new Runnable() {
        @Override
        public void run() {
            mTrafficHandler.sendEmptyMessage(0);
        }
    };

    /*
     *  @hide
     */
    public NetworkTraffic(Context context) {
        this(context, null);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    /*
     *  @hide
     */
    public NetworkTraffic(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        final Resources resources = getResources();
        resolver = mContext.getContentResolver();
        txtSizeSingle = resources.getDimensionPixelSize(R.dimen.net_traffic_single_text_size);
        txtSizeMulti = resources.getDimensionPixelSize(R.dimen.net_traffic_multi_text_size);
        mShouldStart = true;
        updateSettings();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            IntentFilter filter = new IntentFilter();
            filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
            filter.addAction(Intent.ACTION_SCREEN_OFF);
            filter.addAction(Intent.ACTION_SCREEN_ON);
            mContext.registerReceiver(mIntentReceiver, filter, null, getHandler());
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_STATE), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_HIDE), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_ICON), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_INTERVAL), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_TEXT), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_UNIT), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_COLOR_UP), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_COLOR_DOWN), false, mSettingsObserver);
            resolver.registerContentObserver(
                    Settings.System.getUriFor(Settings.System.NETWORK_TRAFFIC_COLOR_ICON), false, mSettingsObserver);
        }
        updateSettings();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            mContext.unregisterReceiver(mIntentReceiver);
            mContext.getContentResolver().unregisterContentObserver(mSettingsObserver);
            mAttached = false;
        }
    }

    private final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null && action.equals(ConnectivityManager.CONNECTIVITY_ACTION)) {
                updateSettings();
            } else if (action != null && action.equals(Intent.ACTION_SCREEN_OFF)){
                mShouldStart = false;
                updateSettings();
            } else if (action != null && action.equals(Intent.ACTION_SCREEN_ON)){
                mShouldStart = true;
                updateSettings();
            }
        }
    };

    private boolean getConnectAvailable() {
        ConnectivityManager connManager =
                (ConnectivityManager) mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkInfo network = (connManager != null) ? connManager.getActiveNetworkInfo() : null;
        return network != null && network.isConnected();
    }

    private void updateSettings() {
        mState = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_STATE, 0);
        mTextEnabled = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_TEXT, 0) != 0;
        mIconEnabled = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_ICON, 1) != 0;
        mHideInactivity = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_HIDE, 0) != 0;       
        mUnit =  Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_UNIT, 1);
        mInterval = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_INTERVAL, 1000);       
        mColorUp = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_COLOR_UP, 0xffffffff);
        mColorDown = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_COLOR_DOWN, 0xffffffff);
        mColorIcon = Settings.System.getInt(resolver, Settings.System.NETWORK_TRAFFIC_COLOR_ICON, 0xffffffff);

        if (mUnit == 1) {
            KB = KILOBYTE;
        } else {
            KB = KILOBIT;
        }
        MB = KB * KB;
        GB = MB * KB;

        updateState();
    }

    private void updateState() {
        if (shouldStartUpdates()) {
            if (getConnectAvailable()) {
                startTrafficUpdates();
                return;
            }
        } else {
            clearHandlerCallbacks();
        }
        setVisibility(View.GONE);
    }

    private void startTrafficUpdates() {
        if (mAttached) {
            totalRxBytes = TrafficStats.getTotalRxBytes();
            lastUpdateTime = SystemClock.elapsedRealtime();
            mTrafficHandler.sendEmptyMessage(1);
        }
        setVisibility(View.VISIBLE);
        updateTrafficDrawable();
    }

    private static boolean isSet(int intState, int intMask) {
        return (intState & intMask) == intMask;
    }

    private int getInterval() {
        return mInterval;
    }

    private void clearHandlerCallbacks() {
        mTrafficHandler.removeCallbacks(mRunnable);
        mTrafficHandler.removeMessages(0);
        mTrafficHandler.removeMessages(1);
    }
    
    private boolean shouldStartUpdates(){
        return mShouldStart && isSet(mState, MASK_UP) || mShouldStart && isSet(mState, MASK_DOWN);
    }

    private void updateTrafficDrawable() {
        int intTrafficDrawable;
        boolean mIconShown = false;
        if(mIconEnabled){
            switch (mState){
                case 0:
                    intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
                break;
                case 1:
                    intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
                break;
                case 2:
                    intTrafficDrawable = R.drawable.stat_sys_network_traffic_down;
                break;
                case 3:
                    intTrafficDrawable = R.drawable.stat_sys_network_traffic_updown;
                break;
                default:
                    intTrafficDrawable = R.drawable.stat_sys_network_traffic_up;
                break;
            }
            Drawable getDrawable = mContext.getResources().getDrawable(intTrafficDrawable);
            getDrawable.setColorFilter(mColorIcon, PorterDuff.Mode.MULTIPLY);
        } else {
            intTrafficDrawable = 0;
        }
        setCompoundDrawablesWithIntrinsicBounds(0, 0, intTrafficDrawable, 0);
    }
}