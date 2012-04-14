/*
 * Copyright 2011, Stuart Douglas
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.fakereplace.api;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.fakereplace.classloading.ClassIdentifier;
import org.fakereplace.com.google.common.collect.MapMaker;

public class ClassChangeNotifier {

    private static final ClassChangeNotifier INSTANCE = new ClassChangeNotifier();

    private static final ThreadLocal<Boolean> NOTIFICATION_IN_PROGRESS = new ThreadLocal<Boolean>() {
        @Override
        protected Boolean initialValue() {
            return false;
        }
    };


    private final Map<ClassLoader, Set<ClassChangeAware>> classChangeAwares = new MapMaker().weakKeys().makeMap();

    public void add(ClassChangeAware aware) {
        if (!classChangeAwares.containsKey(aware.getClass().getClassLoader())) {
            classChangeAwares.put(aware.getClass().getClassLoader(), new HashSet<ClassChangeAware>());
        }
        classChangeAwares.get(aware.getClass().getClassLoader()).add(aware);
    }

     public void notify(Class<?>[] changed, ClassIdentifier[] newClasses) {
        if (!NOTIFICATION_IN_PROGRESS.get()) {
            NOTIFICATION_IN_PROGRESS.set(true);
            try {
                Class<?>[] a = new Class[0];
                for (Set<ClassChangeAware> c : classChangeAwares.values()) {
                    for (ClassChangeAware i : c) {
                        try {
                            i.notify(changed, newClasses);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }
                }
            } finally {
                NOTIFICATION_IN_PROGRESS.set(false);
            }
        }
    }

    public void beforeChange(Class<?>[] changed, ClassIdentifier[] newClasses) {
        Class<?>[] a = new Class[0];
        for (Set<ClassChangeAware> c : classChangeAwares.values()) {
            for (ClassChangeAware i : c) {
                try {
                    i.beforeChange(changed, newClasses);
                } catch (Throwable t) {
                    t.printStackTrace();
                }
            }
        }
    }

    public static ClassChangeNotifier instance() {
        return INSTANCE;
    }
}
