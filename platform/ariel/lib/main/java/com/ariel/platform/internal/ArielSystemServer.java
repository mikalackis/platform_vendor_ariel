/**
 * Copyright (C) 2016 The CyanogenMod Project
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

package com.ariel.platform.internal;

import android.content.Context;
import android.os.SystemProperties;
import android.util.Slog;
import com.android.server.LocalServices;
import com.android.server.SystemServiceManager;
import com.android.server.SystemConfig;
import android.util.ArraySet;
import java.util.Iterator;

import android.os.IDeviceIdleController;
import android.os.PowerManager;
import android.os.ServiceManager;
import android.app.backup.IBackupManager;
import android.os.UserHandle;

import android.os.RemoteException;

import com.ariel.platform.internal.common.ArielSystemServiceHelper;

/**
 * Base CM System Server which handles the starting and states of various CM
 * specific system services. Since its part of the main looper provided by the system
 * server, it will be available indefinitely (until all the things die).
 */
public class ArielSystemServer {
    private static final String TAG = "ArielSystemServer";
    private Context mSystemContext;
    private ArielSystemServiceHelper mSystemServiceHelper;

    private static final String ENCRYPTING_STATE = "trigger_restart_min_framework";
    private static final String ENCRYPTED_STATE = "1";

    private static final String DEVICE_IDLE_SERVICE = "deviceidle";

    private IDeviceIdleController mDeviceIdleService;

    public ArielSystemServer(Context systemContext) {
        Slog.i(TAG, "ArielSystemServer initialized");
        mSystemContext = systemContext;
        mSystemServiceHelper = new ArielSystemServiceHelper(mSystemContext);
    }

    public static boolean coreAppsOnly() {
        // Only run "core" apps+services if we're encrypting the device.
        final String cryptState = SystemProperties.get("vold.decrypt");
        return ENCRYPTING_STATE.equals(cryptState) ||
               ENCRYPTED_STATE.equals(cryptState);
    }

    /**
     * Invoked via reflection by the SystemServer
     */
    private void run() {
        // Start services.
        try {
            Slog.i(TAG, "ArielSystemServer starting services...");
            try {
                            IBackupManager ibm = IBackupManager.Stub.asInterface(
                                    ServiceManager.getService(Context.BACKUP_SERVICE));
                            ibm.setBackupServiceActive(UserHandle.USER_OWNER, true);
                        } catch (RemoteException e) {
                            throw new IllegalStateException("Failed activating backup service.", e);
                        }
            startServices();
            SystemConfig sysConfig = SystemConfig.getInstance();
            ArraySet<String> allowPower = sysConfig.getAllowInPowerSave();
            if(allowPower!=null && allowPower.size()>0){
                Slog.i(TAG, "Power save enabled apps:");
                Iterator<String> it = allowPower.iterator();
                while(it.hasNext()){
                    String packageName = it.next();
                    Slog.i(TAG, "Power save enabled package: "+packageName);
                }
            }
            else{
                Slog.i(TAG, "No power save enabled apps.");
            }
            configureGuardianApp();
        } catch (Throwable ex) {
            Slog.e("System", "******************************************");
            Slog.e("System", "************ Failure starting cm system services", ex);
            throw ex;
        }
    }

    private void configureGuardianApp(){
        mDeviceIdleService = IDeviceIdleController.Stub.asInterface(
                        ServiceManager.getService(DEVICE_IDLE_SERVICE));

        PowerManager power = mSystemContext.getSystemService(PowerManager.class);
        if (power.isIgnoringBatteryOptimizations("com.ariel.guardian")) {
            Slog.i(TAG, "ArielGuardian is already ignoring battery optimizations...");
            return;
        }

        try {
            mDeviceIdleService.addPowerSaveWhitelistApp("com.ariel.guardian");
            Slog.i(TAG, "ArielGuardian is now ignoring battery optimizations...");
        } catch (RemoteException e) {
            Slog.w(TAG, "Unable to reach IDeviceIdleController", e);
        }
    }

    private void startServices() {
        final Context context = mSystemContext;
        final SystemServiceManager ssm = LocalServices.getService(SystemServiceManager.class);
        String[] externalServices = context.getResources().getStringArray(
                com.ariel.platform.internal.R.array.config_externalArielServices);

        for (String service : externalServices) {
            try {
                Slog.i(TAG, "Attempting to start service " + service);
                ArielSystemService arielSystemService =  mSystemServiceHelper.getServiceFor(service);
                if (context.getPackageManager().hasSystemFeature(
                        arielSystemService.getFeatureDeclaration())) {
                    if (coreAppsOnly() && !arielSystemService.isCoreService()) {
                        Slog.d(TAG, "Not starting " + service +
                                " - only parsing core apps");
                    } else {
                        Slog.i(TAG, "Starting service " + service);
                        ssm.startService(arielSystemService.getClass());
                    }
                } else {
                    Slog.i(TAG, "Not starting service " + service +
                            " due to feature not declared on device");
                }
            } catch (Throwable e) {
                reportWtf("starting " + service , e);
            }
        }

        Slog.i(TAG, "ArielSystemServer services started!");
    }

    private void reportWtf(String msg, Throwable e) {
        Slog.w(TAG, "***********************************************");
        Slog.wtf(TAG, "BOOT FAILURE " + msg, e);
    }
}
