/*
 * Copyright (C) 2026 The Yugen Project
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

package com.android.launcher3.util.override;

import android.content.Context;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import com.android.launcher3.util.Executors;
import com.android.launcher3.util.SafeCloseable;

import java.util.concurrent.ExecutionException;

/**
 * A lazily-initialised singleton whose implementation class is named by a string resource, so a
 * derivative can swap the implementation without touching code.
 *
 * <p>This mirrors {@code com.android.launcher3.util.MainThreadInitializedObject}, which only the
 * Go variant compiles, and adds {@link #forOverride}.
 */
public class MainThreadInitializedObject<T extends SafeCloseable> {

    private static final String TAG = "ResourceOverride";

    private final ObjectProvider<T> mProvider;
    private T mValue;

    public MainThreadInitializedObject(ObjectProvider<T> provider) {
        mProvider = provider;
    }

    public T get(Context context) {
        Context app = context.getApplicationContext();
        if (mValue == null) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                mValue = mProvider.get(app);
            } else {
                try {
                    return Executors.MAIN_EXECUTOR.submit(() -> get(context)).get();
                } catch (InterruptedException | ExecutionException e) {
                    throw new RuntimeException(e);
                }
            }
        }
        return mValue;
    }

    /**
     * Returns an instance of {@code clazz}, or of the class named by {@code resId} when it can be
     * created. The override may take either a {@link Context} or no arguments.
     */
    public static <T extends SafeCloseable> MainThreadInitializedObject<T> forOverride(
            Class<T> clazz, int resId) {
        return new MainThreadInitializedObject<>(context -> {
            String className = context.getString(resId);
            if (!TextUtils.isEmpty(className) && !className.equals(clazz.getName())) {
                try {
                    Class<?> override = Class.forName(className, true, context.getClassLoader());
                    try {
                        return clazz.cast(
                                override.getConstructor(Context.class).newInstance(context));
                    } catch (NoSuchMethodException e) {
                        return clazz.cast(override.getDeclaredConstructor().newInstance());
                    }
                } catch (ReflectiveOperationException | ClassCastException e) {
                    Log.e(TAG, "Unable to create " + className + ", using " + clazz.getName(), e);
                }
            }
            try {
                return clazz.getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Unable to create " + clazz.getName(), e);
            }
        });
    }

    public interface ObjectProvider<T> {
        T get(Context context);
    }
}
