/*
 * Copyright (C) 2019 Jorrit "Chainfire" Jongma
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 */

package eu.chainfire.holeylight.misc;

import eu.chainfire.holeylight.BuildConfig;

// Exists only for compatibility with AOSP-copied code
@SuppressWarnings({ "WeakerAccess", "UnusedReturnValue", "unused" })
public class Slog {
    public static int d(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            return android.util.Log.d("HoleyLight/" + tag, msg);
        }
        return 0;
    }

    public static int w(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            return android.util.Log.d("HoleyLight/" + tag, msg);
        }
        return 0;
    }

    public static int i(String tag, String msg) {
        if (BuildConfig.DEBUG) {
            return android.util.Log.d("HoleyLight/" + tag, msg);
        }
        return 0;
    }
}

