/*
 * Copyright 2019 The Android Open Source Project
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

package androidx.camera.camera2.impl;

import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH;
import static android.hardware.camera2.CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
import static android.hardware.camera2.CameraMetadata.CONTROL_AF_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_AUTO;
import static android.hardware.camera2.CameraMetadata.CONTROL_AWB_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_OFF;
import static android.hardware.camera2.CameraMetadata.FLASH_MODE_TORCH;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.camera.camera2.Camera2Config;
import androidx.camera.core.CameraControlInternal;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraX;
import androidx.camera.core.CaptureConfig;
import androidx.camera.core.FlashMode;
import androidx.camera.core.FocusMeteringAction;
import androidx.camera.core.FocusMeteringAction.MeteringMode;
import androidx.camera.core.SensorOrientedMeteringPointFactory;
import androidx.camera.core.SessionConfig;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;
import androidx.camera.testing.CameraUtil;
import androidx.camera.testing.HandlerUtil;
import androidx.core.os.HandlerCompat;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.List;
import java.util.concurrent.ScheduledExecutorService;

@SmallTest
@RunWith(AndroidJUnit4.class)
public final class Camera2CameraControlTest {
    private Camera2CameraControl mCamera2CameraControl;
    private CameraControlInternal.ControlUpdateCallback mControlUpdateCallback;
    private ArgumentCaptor<SessionConfig> mSessionConfigArgumentCaptor =
            ArgumentCaptor.forClass(SessionConfig.class);
    @SuppressWarnings("unchecked")
    private ArgumentCaptor<List<CaptureConfig>> mCaptureConfigArgumentCaptor =
            ArgumentCaptor.forClass(List.class);
    private HandlerThread mHandlerThread;
    private Handler mHandler;
    private CameraCharacteristics mCameraCharacteristics;

    @Before
    public void setUp() throws InterruptedException, CameraAccessException,
            CameraInfoUnavailableException {
        assumeTrue(CameraUtil.hasCameraWithLensFacing(CameraX.LensFacing.BACK));

        Context context = ApplicationProvider.getApplicationContext();
        CameraManager cameraManager = (CameraManager) context.getSystemService(
                Context.CAMERA_SERVICE);
        Camera2CameraFactory camera2CameraFactory = new Camera2CameraFactory(context);
        mCameraCharacteristics = cameraManager.getCameraCharacteristics(
                camera2CameraFactory.cameraIdForLensFacing(CameraX.LensFacing.BACK));

        mControlUpdateCallback = mock(CameraControlInternal.ControlUpdateCallback.class);
        mHandlerThread = new HandlerThread("ControlThread");
        mHandlerThread.start();
        mHandler = HandlerCompat.createAsync(mHandlerThread.getLooper());

        ScheduledExecutorService executorService = CameraXExecutors.newHandlerExecutor(mHandler);
        mCamera2CameraControl = new Camera2CameraControl(mCameraCharacteristics,
                mControlUpdateCallback, executorService, executorService);

        HandlerUtil.waitForLooperToIdle(mHandler);

        // Reset the method call onCameraControlUpdateSessionConfig() in Camera2CameraControl
        // constructor.
        reset(mControlUpdateCallback);
    }

    @After
    public void tearDown() {
        if (mHandlerThread != null) {
            mHandlerThread.quitSafely();
        }
    }

    @Test
    public void setCropRegion_cropRectSetAndRepeatingRequestUpdated() throws InterruptedException {
        Rect rect = new Rect(0, 0, 10, 10);

        mCamera2CameraControl.setCropRegion(rect);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config repeatingConfig = new Camera2Config(sessionConfig.getImplementationOptions());
        assertThat(repeatingConfig.getCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, null))
                .isEqualTo(rect);

        Camera2Config singleConfig = new Camera2Config(mCamera2CameraControl.getSessionOptions());
        assertThat(singleConfig.getCaptureRequestOption(CaptureRequest.SCALER_CROP_REGION, null))
                .isEqualTo(rect);
    }

    @Test
    public void defaultAFAWBMode_ShouldBeCAFWhenNotFocusLocked() {
        Camera2Config singleConfig = new Camera2Config(mCamera2CameraControl.getSessionOptions());
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF))
                .isEqualTo(CaptureRequest.CONTROL_MODE_AUTO);

        assertAfMode(singleConfig, CONTROL_AF_MODE_CONTINUOUS_PICTURE);
        assertAwbMode(singleConfig, CONTROL_AWB_MODE_AUTO);
    }

    @Test
    public void setFlashModeAuto_aeModeSetAndRequestUpdated() throws InterruptedException {
        mCamera2CameraControl.setFlashMode(FlashMode.AUTO);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config camera2Config = new Camera2Config(sessionConfig.getImplementationOptions());

        assertAeMode(camera2Config, CONTROL_AE_MODE_ON_AUTO_FLASH);
        assertThat(mCamera2CameraControl.getFlashMode()).isEqualTo(FlashMode.AUTO);
    }

    @Test
    public void setFlashModeOff_aeModeSetAndRequestUpdated() throws InterruptedException {
        mCamera2CameraControl.setFlashMode(FlashMode.OFF);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config camera2Config = new Camera2Config(sessionConfig.getImplementationOptions());

        assertAeMode(camera2Config, CONTROL_AE_MODE_ON);

        assertThat(mCamera2CameraControl.getFlashMode()).isEqualTo(FlashMode.OFF);
    }

    @Test
    public void setFlashModeOn_aeModeSetAndRequestUpdated() throws InterruptedException {
        mCamera2CameraControl.setFlashMode(FlashMode.ON);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config camera2Config = new Camera2Config(sessionConfig.getImplementationOptions());

        assertAeMode(camera2Config, CONTROL_AE_MODE_ON_ALWAYS_FLASH);

        assertThat(mCamera2CameraControl.getFlashMode()).isEqualTo(FlashMode.ON);
    }

    @Test
    public void enableTorch_aeModeSetAndRequestUpdated() throws InterruptedException {
        mCamera2CameraControl.enableTorch(true);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config camera2Config = new Camera2Config(sessionConfig.getImplementationOptions());

        assertAeMode(camera2Config, CONTROL_AE_MODE_ON);

        assertThat(
                camera2Config.getCaptureRequestOption(
                        CaptureRequest.FLASH_MODE, FLASH_MODE_OFF))
                .isEqualTo(FLASH_MODE_TORCH);
        assertThat(mCamera2CameraControl.isTorchOn()).isTrue();
    }

    @Test
    public void disableTorchFlashModeAuto_aeModeSetAndRequestUpdated() throws InterruptedException {
        mCamera2CameraControl.setFlashMode(FlashMode.AUTO);
        mCamera2CameraControl.enableTorch(false);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(2)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getAllValues().get(0);
        Camera2Config camera2Config = new Camera2Config(sessionConfig.getImplementationOptions());

        assertAeMode(camera2Config, CONTROL_AE_MODE_ON_AUTO_FLASH);

        assertThat(camera2Config.getCaptureRequestOption(CaptureRequest.FLASH_MODE, -1))
                .isEqualTo(-1);
        assertThat(mCamera2CameraControl.isTorchOn()).isFalse();

        verify(mControlUpdateCallback, times(1)).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());
        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());

        assertAeMode(resultCaptureConfig, CONTROL_AE_MODE_ON);

    }

    @Test
    public void triggerAf_captureRequestSent() throws InterruptedException {
        mCamera2CameraControl.triggerAf();

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());
        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());
        assertThat(
                resultCaptureConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_TRIGGER, null))
                .isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_START);
    }

    @Test
    public void triggerAePrecapture_captureRequestSent() throws InterruptedException {
        mCamera2CameraControl.triggerAePrecapture();

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());
        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());
        assertThat(
                resultCaptureConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, null))
                .isEqualTo(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
    }

    @Test
    public void cancelAfAeTrigger_captureRequestSent() throws InterruptedException {
        mCamera2CameraControl.cancelAfAeTrigger(true, true);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());
        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());
        assertThat(
                resultCaptureConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_TRIGGER, null))
                .isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);

        if (Build.VERSION.SDK_INT >= 23) {
            assertThat(
                    resultCaptureConfig.getCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, null))
                    .isEqualTo(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
        }
    }

    @Test
    public void cancelAfTrigger_captureRequestSent() throws InterruptedException {
        mCamera2CameraControl.cancelAfAeTrigger(true, false);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());
        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());
        assertThat(
                resultCaptureConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_TRIGGER, null))
                .isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
        assertThat(
                resultCaptureConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, null))
                .isNull();
    }

    @Test
    public void cancelAeTrigger_captureRequestSent() throws InterruptedException {
        mCamera2CameraControl.cancelAfAeTrigger(false, true);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());
        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());

        assertThat(
                resultCaptureConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_TRIGGER, null))
                .isNull();

        if (Build.VERSION.SDK_INT >= 23) {
            assertThat(
                    resultCaptureConfig.getCaptureRequestOption(
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, null))
                    .isEqualTo(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
        }
    }

    @Test
    public void startFocusAndMetering_3ARegionsUpdatedInSessionAndSessionOptions()
            throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0))
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);

        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config repeatingConfig = new Camera2Config(sessionConfig.getImplementationOptions());

        // Here we verify only 3A region count is correct.  Values correctness are left to
        // FocusMeteringControlTest.
        assertThat(
                repeatingConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_REGIONS, null)).hasLength(1);
        assertThat(
                repeatingConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_REGIONS, null)).hasLength(1);
        assertThat(
                repeatingConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_REGIONS, null)).hasLength(1);


        Camera2Config singleConfig = new Camera2Config(mCamera2CameraControl.getSessionOptions());
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_REGIONS, null)).hasLength(1);
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_REGIONS, null)).hasLength(1);
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_REGIONS, null)).hasLength(1);
    }

    @Test
    public void startFocusAndMetering_AfIsTriggeredProperly() throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0))
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);
        HandlerUtil.waitForLooperToIdle(mHandler);

        verifyAfMode(CaptureRequest.CONTROL_AF_MODE_AUTO);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());

        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());

        // Trigger AF
        assertThat(resultCaptureConfig.getCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER,
                null)).isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_START);
    }

    @Test
    public void startFocusAndMetering_AFNotInvolved_AfIsNotTriggered() throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0),
                MeteringMode.AE_AWB)
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);
        HandlerUtil.waitForLooperToIdle(mHandler);

        verifyAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        verify(mControlUpdateCallback, never()).onCameraControlCaptureRequests(any());
    }

    @Test
    public void cancelFocusAndMetering_3ARegionsReset() throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0))
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);
        HandlerUtil.waitForLooperToIdle(mHandler);
        Mockito.reset(mControlUpdateCallback);

        mCamera2CameraControl.cancelFocusAndMetering();
        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config repeatingConfig = new Camera2Config(sessionConfig.getImplementationOptions());

        assertThat(
                repeatingConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_REGIONS, null)).isNull();
        assertThat(
                repeatingConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_REGIONS, null)).isNull();
        assertThat(
                repeatingConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_REGIONS, null)).isNull();


        Camera2Config singleConfig = new Camera2Config(mCamera2CameraControl.getSessionOptions());
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AF_REGIONS, null)).isNull();
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AE_REGIONS, null)).isNull();
        assertThat(
                singleConfig.getCaptureRequestOption(
                        CaptureRequest.CONTROL_AWB_REGIONS, null)).isNull();
    }

    @Test
    public void cancelFocusAndMetering_cancelAfProperly() throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0))
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);
        HandlerUtil.waitForLooperToIdle(mHandler);
        Mockito.reset(mControlUpdateCallback);
        mCamera2CameraControl.cancelFocusAndMetering();
        HandlerUtil.waitForLooperToIdle(mHandler);

        verifyAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

        verify(mControlUpdateCallback).onCameraControlCaptureRequests(
                mCaptureConfigArgumentCaptor.capture());

        CaptureConfig captureConfig = mCaptureConfigArgumentCaptor.getValue().get(0);
        Camera2Config resultCaptureConfig =
                new Camera2Config(captureConfig.getImplementationOptions());

        // Trigger AF
        assertThat(resultCaptureConfig.getCaptureRequestOption(CaptureRequest.CONTROL_AF_TRIGGER,
                null)).isEqualTo(CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
    }

    private void verifyAfMode(int expectAfMode) {
        verify(mControlUpdateCallback, times(1)).onCameraControlUpdateSessionConfig(
                mSessionConfigArgumentCaptor.capture());
        SessionConfig sessionConfig = mSessionConfigArgumentCaptor.getValue();
        Camera2Config repeatingConfig = new Camera2Config(sessionConfig.getImplementationOptions());
        assertAfMode(repeatingConfig, expectAfMode);
    }

    @Test
    public void cancelFocusAndMetering_AFNotInvolved_notCancelAF() throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0),
                MeteringMode.AE_ONLY)
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);
        HandlerUtil.waitForLooperToIdle(mHandler);
        Mockito.reset(mControlUpdateCallback);
        mCamera2CameraControl.cancelFocusAndMetering();
        HandlerUtil.waitForLooperToIdle(mHandler);

        verify(mControlUpdateCallback, never()).onCameraControlCaptureRequests(any());

        verifyAfMode(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    }

    @Test
    public void startFocus_afModeIsSetToAuto() throws InterruptedException {
        SensorOrientedMeteringPointFactory factory = new SensorOrientedMeteringPointFactory(1.0f,
                1.0f);
        FocusMeteringAction action = FocusMeteringAction.Builder.from(factory.createPoint(0, 0))
                .build();
        mCamera2CameraControl.startFocusAndMetering(action);
        HandlerUtil.waitForLooperToIdle(mHandler);

        Camera2Config singleConfig = new Camera2Config(mCamera2CameraControl.getSessionOptions());
        assertAfMode(singleConfig, CaptureRequest.CONTROL_AF_MODE_AUTO);

        mCamera2CameraControl.cancelFocusAndMetering();
        HandlerUtil.waitForLooperToIdle(mHandler);

        Camera2Config singleConfig2 = new Camera2Config(mCamera2CameraControl.getSessionOptions());
        assertAfMode(singleConfig2, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
    }

    private boolean isAfModeSupported(int afMode) {
        int[] modes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        return isModeInList(afMode, modes);
    }

    private boolean isAeModeSupported(int aeMode) {
        int[] modes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        return isModeInList(aeMode, modes);
    }

    private boolean isAwbModeSupported(int awbMode) {
        int[] modes = mCameraCharacteristics.get(CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        return isModeInList(awbMode, modes);
    }


    private boolean isModeInList(int mode, int[] modeList) {
        if (modeList == null) {
            return false;
        }
        for (int m : modeList) {
            if (mode == m) {
                return true;
            }
        }
        return false;
    }

    private void assertAfMode(Camera2Config config, int afMode) {
        if (isAfModeSupported(afMode)) {
            assertThat(config.getCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, null)).isEqualTo(afMode);
        } else {
            int fallbackMode;
            if (isAfModeSupported(CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
                fallbackMode = CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            } else if (isAfModeSupported(CONTROL_AF_MODE_AUTO)) {
                fallbackMode = CONTROL_AF_MODE_AUTO;
            } else {
                fallbackMode = CONTROL_AF_MODE_OFF;
            }

            assertThat(config.getCaptureRequestOption(
                    CaptureRequest.CONTROL_AF_MODE, null)).isEqualTo(fallbackMode);
        }
    }

    private void assertAeMode(Camera2Config config, int aeMode) {
        if (isAeModeSupported(aeMode)) {
            assertThat(config.getCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE, null)).isEqualTo(aeMode);
        } else {
            int fallbackMode;
            if (isAeModeSupported(CONTROL_AE_MODE_ON)) {
                fallbackMode = CONTROL_AE_MODE_ON;
            } else {
                fallbackMode = CONTROL_AE_MODE_OFF;
            }

            assertThat(config.getCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_MODE, null)).isEqualTo(fallbackMode);
        }
    }

    private void assertAwbMode(Camera2Config config, int awbMode) {
        if (isAwbModeSupported(awbMode)) {
            assertThat(config.getCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE, null)).isEqualTo(awbMode);
        } else {
            int fallbackMode;
            if (isAwbModeSupported(CONTROL_AWB_MODE_AUTO)) {
                fallbackMode = CONTROL_AWB_MODE_AUTO;
            } else {
                fallbackMode = CONTROL_AWB_MODE_OFF;
            }

            assertThat(config.getCaptureRequestOption(
                    CaptureRequest.CONTROL_AWB_MODE, null)).isEqualTo(fallbackMode);
        }
    }
}
