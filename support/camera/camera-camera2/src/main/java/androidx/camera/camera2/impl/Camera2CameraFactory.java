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

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.os.Handler;
import android.os.HandlerThread;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RestrictTo;
import androidx.annotation.RestrictTo.Scope;
import androidx.camera.camera2.impl.compat.CameraManagerCompat;
import androidx.camera.core.BaseCamera;
import androidx.camera.core.CameraFactory;
import androidx.camera.core.CameraInfoUnavailableException;
import androidx.camera.core.CameraX.LensFacing;
import androidx.camera.core.CameraXThreads;
import androidx.camera.core.LensFacingCameraIdFilter;
import androidx.camera.core.impl.utils.executor.CameraXExecutors;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The factory class that creates {@link Camera2CameraImpl} instances.
 *
 * @hide
 */
@RestrictTo(Scope.LIBRARY)
public final class Camera2CameraFactory implements CameraFactory {
    private static final String TAG = "Camera2CameraFactory";

    private static final int DEFAULT_ALLOWED_CONCURRENT_OPEN_CAMERAS = 1;

    private static final HandlerThread sHandlerThread = new HandlerThread(CameraXThreads.TAG);
    private static final Handler sHandler;
    private final CameraAvailabilityRegistry mAvailabilityRegistry;

    static {
        sHandlerThread.start();
        sHandler = new Handler(sHandlerThread.getLooper());
    }

    private final CameraManagerCompat mCameraManager;

    public Camera2CameraFactory(@NonNull Context context) {
        mCameraManager = CameraManagerCompat.from(context);
        mAvailabilityRegistry = new CameraAvailabilityRegistry(
                DEFAULT_ALLOWED_CONCURRENT_OPEN_CAMERAS,
                CameraXExecutors.newHandlerExecutor(sHandler));
    }

    @Override
    @NonNull
    public BaseCamera getCamera(@NonNull String cameraId) {
        Camera2CameraImpl camera2CameraImpl = new Camera2CameraImpl(mCameraManager, cameraId,
                mAvailabilityRegistry.getAvailableCameraCount(), sHandler);
        mAvailabilityRegistry.registerCamera(camera2CameraImpl);
        return camera2CameraImpl;
    }

    @Override
    @NonNull
    public Set<String> getAvailableCameraIds() throws CameraInfoUnavailableException {
        List<String> camerasList = null;
        try {
            camerasList = Arrays.asList(mCameraManager.unwrap().getCameraIdList());
        } catch (CameraAccessException e) {
            throw new CameraInfoUnavailableException(
                    "Unable to retrieve list of cameras on device.", e);
        }
        // Use a LinkedHashSet to preserve order
        return new LinkedHashSet<>(camerasList);
    }

    @Override
    @Nullable
    public String cameraIdForLensFacing(@NonNull LensFacing lensFacing)
            throws CameraInfoUnavailableException {
        Set<String> availableCameraIds = getLensFacingCameraIdFilter(
                lensFacing).filter(getAvailableCameraIds());

        if (!availableCameraIds.isEmpty()) {
            return availableCameraIds.iterator().next();
        } else {
            return null;
        }
    }

    @Override
    @NonNull
    public LensFacingCameraIdFilter getLensFacingCameraIdFilter(@NonNull LensFacing lensFacing) {
        return new Camera2LensFacingCameraIdFilter(mCameraManager.unwrap(), lensFacing);
    }
}
