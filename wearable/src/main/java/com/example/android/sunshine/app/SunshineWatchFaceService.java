/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.wearable.Asset;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataItem;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.Wearable;

import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Sunshine watch face based on Android Studio's Sample digital watch face with blinking colon.
 * In ambient mode, the colon doesn't blink. On devices with low-bit ambient mode, the text is
 * drawn without anti-aliasing in ambient mode. On devices which require burn-in protection,
 * the hours are drawn in normal rather than bold. The time is drawn with less contrast in mute mode.
 */
public class SunshineWatchFaceService extends CanvasWatchFaceService {
    private static final String TAG = "SunshinWatchFaceService";

    private static final Typeface BOLD_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD);
    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    /**
     * Update rate in milliseconds for mute mode. We update every minute, like in ambient mode.
     */
    private static final long MUTE_UPDATE_RATE_MS = TimeUnit.MINUTES.toMillis(1);

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks, GoogleApiClient.OnConnectionFailedListener {
        static final String COLON_STRING = ":";

        /** Alpha value for drawing time when in mute mode. */
        static final int MUTE_ALPHA = 100;

        /** Alpha value for drawing time when not in mute mode. */
        static final int NORMAL_ALPHA = 255;

        static final int MSG_UPDATE_TIME = 0;

        // Keys to the DataItems sent by the mobile app
        private static final String HIGH_TEMP_KEY = "com.example.android.sunshine.app.sync.key.high";
        private static final String LOW_TEMP_KEY = "com.example.android.sunshine.app.sync.key.low";
        public static final String ICON_KEY = "com.example.android.sunshine.app.sync.key.icon";

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        /** Handler to update the time periodically in interactive mode. */
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        if (Log.isLoggable(TAG, Log.VERBOSE)) {
                            Log.v(TAG, "updating time");
                        }
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        // GooleApiClient to connect to in order to receive messages and DataItems from the mobile
        // app
        GoogleApiClient mGoogleApiClient;

        /**
         * Handles time zone and locale changes.
         */
        final BroadcastReceiver mReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
                invalidate();
            }
        };

        /**
         * Unregistering an unregistered receiver throws an exception. Keep track of the
         * registration state to prevent that.
         */
        boolean mRegisteredReceiver = false;

        Paint mBackgroundPaint;
        Paint mDatePaint;
        Paint mHourPaint;
        Paint mMinutePaint;
        Paint mColonPaint;
        Paint mHighTempPaint;
        Paint mLowTempPaint;
        float mColonWidth;
        boolean mMute;

        Calendar mCalendar;
        Date mDate;
        SimpleDateFormat mDayOfWeekFormat;
        java.text.DateFormat mDateFormat;

        // Initialize temperatures
        String mHighTemp = "";
        String mLowTemp = "";

        Bitmap mWeatherIcon;
        Bitmap mGrayWeatherBitmap;

        boolean mShouldDrawColons;
        float mXOffset;
        float mYOffset;
        float mXDayOffset;
        float mXDateOffset;
        float mLineHeight;
        int mInteractiveBackgroundColor =
                getResources().getColor(R.color.background_sunshine);
        int mInteractiveHourDigitsColor = Color.WHITE;
        int mInteractiveMinuteDigitsColor = Color.WHITE;
        int mInteractiveHighTempColor = Color.WHITE;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;
        boolean mBurnInProtection;

        @Override
        public void onCreate(SurfaceHolder holder) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onCreate");
            }
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(SunshineWatchFaceService.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .build());
            Resources resources = SunshineWatchFaceService.this.getResources();
            mLineHeight = resources.getDimension(R.dimen.digital_line_height);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(mInteractiveBackgroundColor);
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_date));
            mHourPaint = createTextPaint(mInteractiveHourDigitsColor, BOLD_TYPEFACE);
            mMinutePaint = createTextPaint(mInteractiveMinuteDigitsColor);
            mColonPaint = createTextPaint(resources.getColor(R.color.digital_text));
            mHighTempPaint = createTextPaint(mInteractiveHighTempColor);
            mLowTempPaint = createTextPaint(resources.getColor(R.color.low_temp));

            mCalendar = Calendar.getInstance();
            mDate = new Date();
            initFormats();

            // Initialize and connect to GoolgeApiClient so we are ready to receive messages and
            // DataItems from the mobile app
            mGoogleApiClient = new GoogleApiClient.Builder(SunshineWatchFaceService.this)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .addApi(Wearable.API)
                    .build();
            mGoogleApiClient.connect();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int defaultInteractiveColor) {
            return createTextPaint(defaultInteractiveColor, NORMAL_TYPEFACE);
        }

        private Paint createTextPaint(int defaultInteractiveColor, Typeface typeface) {
            Paint paint = new Paint();
            paint.setColor(defaultInteractiveColor);
            paint.setTypeface(typeface);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onVisibilityChanged: " + visible);
            }

            super.onVisibilityChanged(visible);

            if (visible) {

                registerReceiver();

                // Update time zone and date formats, in case they changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                initFormats();
            } else {
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }



        private void initFormats() {
            // For the day of the week only the first 3 letters are shown.
            mDayOfWeekFormat = new SimpleDateFormat("EEE,", Locale.getDefault());
            mDayOfWeekFormat.setCalendar(mCalendar);
            mDateFormat = new SimpleDateFormat("MMM dd yyyy", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
        }

        private void registerReceiver() {
            if (mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            filter.addAction(Intent.ACTION_LOCALE_CHANGED);
            SunshineWatchFaceService.this.registerReceiver(mReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredReceiver) {
                return;
            }
            mRegisteredReceiver = false;
            SunshineWatchFaceService.this.unregisterReceiver(mReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onApplyWindowInsets: " + (insets.isRound() ? "round" : "square"));
            }
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = SunshineWatchFaceService.this.getResources();
            boolean isRound = insets.isRound();
            mXOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_round : R.dimen.digital_x_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_text_size_round : R.dimen.digital_text_size);
            mYOffset = resources.getDimension(isRound
                    ? R.dimen.digital_y_offset_round : R.dimen.digital_y_offset);
            mXDayOffset = resources.getDimension(isRound
                    ? R.dimen.digital_day_x_offset_round : R.dimen.digital_day_x_offset);
            mXDateOffset = resources.getDimension(isRound
                    ? R.dimen.digital_date_x_offset_round : R.dimen.digital_date_x_offset);

            mDatePaint.setTextSize(resources.getDimension(R.dimen.digital_date_text_size));
            mHourPaint.setTextSize(textSize);
            mMinutePaint.setTextSize(textSize);
            mColonPaint.setTextSize(textSize);
            mHighTempPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));
            mLowTempPaint.setTextSize(resources.getDimension(R.dimen.temp_text_size));

            mColonWidth = mColonPaint.measureText(COLON_STRING);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);

            mBurnInProtection = properties.getBoolean(PROPERTY_BURN_IN_PROTECTION, false);
            mHourPaint.setTypeface(mBurnInProtection ? NORMAL_TYPEFACE : BOLD_TYPEFACE);

            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);

            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onPropertiesChanged: burn-in protection = " + mBurnInProtection
                        + ", low-bit ambient = " + mLowBitAmbient);
            }
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onTimeTick: ambient = " + isInAmbientMode());
            }
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onAmbientModeChanged: " + inAmbientMode);
            }
            adjustPaintColorToCurrentMode(mBackgroundPaint, mInteractiveBackgroundColor,
                    Color.BLACK);
            adjustPaintColorToCurrentMode(mHourPaint, mInteractiveHourDigitsColor,
                    Color.WHITE);
            adjustPaintColorToCurrentMode(mMinutePaint, mInteractiveMinuteDigitsColor,
                    Color.WHITE);

            if (mLowBitAmbient) {
                boolean antiAlias = !inAmbientMode;
                mDatePaint.setAntiAlias(antiAlias);
                mHourPaint.setAntiAlias(antiAlias);
                mMinutePaint.setAntiAlias(antiAlias);
                mColonPaint.setAntiAlias(antiAlias);
                mHighTempPaint.setAntiAlias(antiAlias);
                mLowTempPaint.setAntiAlias(antiAlias);
            }

            //Create a gray version of the weather icon to display in ambient mode
            initGrayWeatherBitmap();
            invalidate();

            // Whether the timer should be running depends on whether we're in ambient mode (as well
            // as whether we're visible), so we may need to start or stop the timer.
            updateTimer();
        }

        private void initGrayWeatherBitmap() {

            if(mWeatherIcon != null) {
                mGrayWeatherBitmap = Bitmap.createBitmap(
                        mWeatherIcon.getWidth(),
                        mWeatherIcon.getHeight(),
                        Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(mGrayWeatherBitmap);
                Paint grayPaint = new Paint();
                ColorMatrix colorMatrix = new ColorMatrix();
                colorMatrix.setSaturation(0);
                ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
                grayPaint.setColorFilter(filter);
                canvas.drawBitmap(mWeatherIcon, 0, 0, grayPaint);
            }
        }

        private void adjustPaintColorToCurrentMode(Paint paint, int interactiveColor,
                                                   int ambientColor) {
            paint.setColor(isInAmbientMode() ? ambientColor : interactiveColor);
        }

        @Override
        public void onInterruptionFilterChanged(int interruptionFilter) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onInterruptionFilterChanged: " + interruptionFilter);
            }
            super.onInterruptionFilterChanged(interruptionFilter);

            boolean inMuteMode = interruptionFilter == WatchFaceService.INTERRUPTION_FILTER_NONE;
            // We only need to update once a minute in mute mode.
            setInteractiveUpdateRateMs(inMuteMode ? MUTE_UPDATE_RATE_MS : NORMAL_UPDATE_RATE_MS);

            if (mMute != inMuteMode) {
                mMute = inMuteMode;
                int alpha = inMuteMode ? MUTE_ALPHA : NORMAL_ALPHA;
                mDatePaint.setAlpha(alpha);
                mHourPaint.setAlpha(alpha);
                mMinutePaint.setAlpha(alpha);
                mColonPaint.setAlpha(alpha);
                mHighTempPaint.setAlpha(alpha);
                mLowTempPaint.setAlpha(alpha);
                invalidate();
            }
        }

        public void setInteractiveUpdateRateMs(long updateRateMs) {
            if (updateRateMs == mInteractiveUpdateRateMs) {
                return;
            }
            mInteractiveUpdateRateMs = updateRateMs;

            // Stop and restart the timer so the new update rate takes effect immediately.
            if (shouldTimerBeRunning()) {
                updateTimer();
            }
        }

        private void updatePaintIfInteractive(Paint paint, int interactiveColor) {
            if (!isInAmbientMode() && paint != null) {
                paint.setColor(interactiveColor);
            }
        }

        private String formatTwoDigitNumber(int hour) {
            return String.format("%02d", hour);
        }


        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);
            mDate.setTime(now);
            boolean is24Hour = true;

            // Show colons for the first half of each second so the colons blink on when the time
            // updates.
            mShouldDrawColons = (System.currentTimeMillis() % 1000) < 500;

            // Draw the background.
            canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);

            // Draw the hours.
            float x = mXOffset;
            String hourString;
            if (is24Hour) {
                hourString = formatTwoDigitNumber(mCalendar.get(Calendar.HOUR_OF_DAY));
            } else {
                int hour = mCalendar.get(Calendar.HOUR);
                if (hour == 0) {
                    hour = 12;
                }
                hourString = String.valueOf(hour);
            }
            canvas.drawText(hourString, x, mYOffset, mHourPaint);
            x += mHourPaint.measureText(hourString);

            // In ambient and mute modes, always draw the first colon. Otherwise, draw the
            // first colon for the first half of each second.
            if (isInAmbientMode() || mMute || mShouldDrawColons) {
                canvas.drawText(COLON_STRING, x, mYOffset, mColonPaint);
            }
            x += mColonWidth;

            // Draw the minutes.
            String minuteString = formatTwoDigitNumber(mCalendar.get(Calendar.MINUTE));
            canvas.drawText(minuteString, x, mYOffset, mMinutePaint);
            x += mMinutePaint.measureText(minuteString);

            // Only render the day of week, date  and weather info if there is no peek card, so
            // they do not bleed into each other in ambient mode.
            if (getPeekCardPosition().isEmpty()) {

                // Draw Day of week
                canvas.drawText(
                        mDayOfWeekFormat.format(mDate),
                        mXOffset-mXDayOffset, mYOffset + mLineHeight, mDatePaint);

                // Draw Date
                canvas.drawText(
                        mDateFormat.format(mDate),
                        mXOffset+mXDateOffset, mYOffset + mLineHeight, mDatePaint);

                // Separation line between weather data and time/date
                canvas.drawLine(mXOffset + 60, mYOffset + mLineHeight*2, mXOffset + 110, mYOffset + mLineHeight*2, mDatePaint);

                // Draw weather icon
                if(mWeatherIcon != null){

                    if (isInAmbientMode() && (mLowBitAmbient || mBurnInProtection)) {
                        // Don't display the icon
                    } else if (isInAmbientMode()) {
                        canvas.drawBitmap(mGrayWeatherBitmap, mXOffset - 20, mYOffset + mLineHeight*2.6f, null);
                    } else {
                        canvas.drawBitmap(mWeatherIcon, mXOffset - 20, mYOffset + mLineHeight*2.6f, null);
                    }
                }

                // Draw Temperature
                String degreeSymbol = "";

                // No need to display the degree symbol when the temperature is not available
                if(!mHighTemp.isEmpty())
                    degreeSymbol = "\u00b0";

                // Draw high temperature
                canvas.drawText(mHighTemp + degreeSymbol, mXOffset+50, mYOffset + mLineHeight*3.75f, mHighTempPaint);

                if(!mLowTemp.isEmpty())
                    degreeSymbol = "\u00b0";

                // Draw low temperature
                canvas.drawText(mLowTemp + degreeSymbol, mXOffset+120, mYOffset + mLineHeight*3.75f, mLowTempPaint);
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "updateTimer");
            }
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }


        @Override // DataApi.DataListener
        public void onDataChanged(DataEventBuffer dataEvents) {
            Log.d(TAG, "onDataChanged");

            for (DataEvent dataEvent : dataEvents) {
                if (dataEvent.getType() == DataEvent.TYPE_CHANGED) {
                    // DataItem changed
                    DataItem item = dataEvent.getDataItem();

                    // Check if we received new weather info from the mobile app
                    if (item.getUri().getPath().compareTo("/temp") == 0) {
                        DataMap dataMap = DataMapItem.fromDataItem(item).getDataMap();

                        Log.d(TAG, "Max temperature received: " + dataMap.getDouble(HIGH_TEMP_KEY)
                                + " Low temperature received: " +  dataMap.getDouble(LOW_TEMP_KEY));

                        // Update values to redraw UI
                        mHighTemp = String.valueOf((Double.valueOf(Math.round(dataMap.getDouble(HIGH_TEMP_KEY)))).intValue());
                        mLowTemp = String.valueOf((Double.valueOf(Math.round(dataMap.getDouble(LOW_TEMP_KEY)))).intValue());

                        // Get weather icon as asset
                        Asset asset  = dataMap.getAsset(ICON_KEY);
                        Log.d(TAG, "Asset received");
                        new LoadBitmapFromAssetTask().execute(asset);
                        // Invalidate the UI for WatchFace when the AsyncTask is completed
                    }
                }
            }
        }

        // Google Api callbacks
        @Override
        public void onConnected(Bundle connectionHint) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnected: " + connectionHint);
            }
            Wearable.DataApi.addListener(mGoogleApiClient, Engine.this);
        }

        @Override
        public void onConnectionSuspended(int cause) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionSuspended: " + cause);
            }
        }

        @Override
        public void onConnectionFailed(ConnectionResult result) {
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "onConnectionFailed: " + result);
            }
        }

        private class LoadBitmapFromAssetTask extends AsyncTask<Asset, Void, Bitmap>{
            // AsyncTsk that takes the asset the mobile app sent, converts into a file descriptor
            // and finally decodes it into a bitmap for display on the WatchFace

            protected Bitmap doInBackground(Asset... params){

                Asset asset = params[0];

                if (asset == null) {
                    throw new IllegalArgumentException("Asset must be non-null");
                }

                ConnectionResult result =
                        mGoogleApiClient.blockingConnect(5000, TimeUnit.MILLISECONDS);

                if (!result.isSuccess()) {
                    return null;
                }

                // Convert asset into a file descriptor and block until it's ready
                InputStream assetInputStream = Wearable.DataApi.getFdForAsset(
                        mGoogleApiClient, asset).await().getInputStream();

                if (assetInputStream == null) {
                    Log.w(TAG, "Requested an unknown asset.");
                    return null;
                }
                // decode the stream into a bitmap
                return BitmapFactory.decodeStream(assetInputStream);

            }

            protected void onPostExecute(Bitmap bitmap){
                // Scale the bitmap
                mWeatherIcon = Bitmap.createScaledBitmap(bitmap,
                        (int) (bitmap.getWidth() * 0.45),
                        (int) (bitmap.getHeight() * 0.45), true);
                // Invalidate UI to redraw the WatchFace
                invalidate();
            }

        }
    }
}