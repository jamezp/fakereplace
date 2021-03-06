/*
 * Copyright 2012, Stuart Douglas, and individual contributors as indicated
 * by the @authors tag.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.fakereplace.core;

import javassist.bytecode.ClassFile;
import org.fakereplace.api.Extension;
import org.fakereplace.api.NewClassData;
import org.fakereplace.classloading.ClassLookupManager;
import org.fakereplace.data.ClassDataStore;
import org.fakereplace.replacement.AddedClass;
import org.fakereplace.replacement.ClassRedefiner;
import org.fakereplace.replacement.ReplacementResult;
import org.fakereplace.replacement.notification.CurrentChangedClasses;
import org.fakereplace.server.FakereplaceServer;
import org.fakereplace.transformation.ClassLoaderTransformer;
import org.fakereplace.transformation.MainTransformer;
import org.fakereplace.transformation.UnmodifiedFileIndex;

import java.beans.Introspector;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.instrument.ClassDefinition;
import java.lang.instrument.Instrumentation;
import java.lang.instrument.UnmodifiableClassException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * The agent entry point.
 *
 * @author stuart
 */
public class Agent {

    private static final Class[] EMPTY_CL_ARRAY = new Class[0];

    private static volatile Instrumentation inst;

    private static volatile MainTransformer mainTransformer;


    public static void premain(java.lang.String s, java.lang.instrument.Instrumentation i) {

        AgentOptions.setup(s);
        inst = i;

        final Set<Extension> extension = getIntegrationInfo(ClassLoader.getSystemClassLoader());

        //initialise the unmodified file index
        UnmodifiedFileIndex.loadIndex();

        //first we need to instrument the class loaders
        final Set<Class> cls = new HashSet<Class>();
        for (Class c : inst.getAllLoadedClasses()) {
            if (ClassLoader.class.isAssignableFrom(c)) {
                cls.add(c);
            }
        }

        final ClassLoaderTransformer classLoaderTransformer = new ClassLoaderTransformer();
        final MainTransformer mainTransformer = new MainTransformer(extension);
        Agent.mainTransformer = mainTransformer;
        inst.addTransformer(mainTransformer, true);

        mainTransformer.addTransformer(classLoaderTransformer);

        try {
            inst.retransformClasses(cls.toArray(EMPTY_CL_ARRAY));
        } catch (UnmodifiableClassException e) {
            e.printStackTrace();
        }
        mainTransformer.addTransformer(new Transformer(extension));

        //start the server
        Thread thread = new Thread(new FakereplaceServer(Integer.parseInt(AgentOptions.getOption(AgentOption.PORT))));
        thread.setDaemon(true);
        thread.setName("Fakereplace Thread");
        thread.start();
    }

    public static void redefine(ClassDefinition[] classes, AddedClass[] addedData) throws UnmodifiableClassException, ClassNotFoundException {
        try {
            final List<NewClassData> addedClass = new ArrayList<>();
            for (AddedClass i : addedData) {
                ClassFile cf = new ClassFile(new DataInputStream(new ByteArrayInputStream(i.getData())));
                addedClass.add(new NewClassData(i.getClassName(), i.getLoader(), cf));
            }

            final List<Class<?>> changedClasses = new ArrayList<>();
            for (ClassDefinition i : classes) {

                System.out.println("Fakereplace is replacing class " + i.getDefinitionClass());
                changedClasses.add(i.getDefinitionClass());
                ClassDataStore.instance().markClassReplaced(i.getClass());
            }
            // notify the integration classes that stuff is about to change
            ClassChangeNotifier.instance().beforeChange(Collections.unmodifiableList(changedClasses), Collections.unmodifiableList(addedClass));
            CurrentChangedClasses.prepareClasses(changedClasses);
            // re-write the classes so their field
            ReplacementResult result = ClassRedefiner.rewriteLoadedClasses(classes);
            for (AddedClass c : addedData) {
                ClassLookupManager.addClassInfo(c.getClassName(), c.getLoader(), c.getData());
            }
            inst.redefineClasses(result.getClasses());
            if (!result.getClassesToRetransform().isEmpty()) {
                inst.retransformClasses(result.getClassesToRetransform().toArray(new Class[result.getClassesToRetransform().size()]));
            }
            Introspector.flushCaches();

            ClassChangeNotifier.instance().afterChange(Collections.unmodifiableList(CurrentChangedClasses.getChanged()), Collections.unmodifiableList(addedClass));
        } catch (Throwable e) {
            try {
                // dump the classes to /tmp so we can look at them
                for (ClassDefinition d : classes) {
                    try {
                        ByteArrayInputStream bin = new ByteArrayInputStream(d.getDefinitionClassFile());
                        DataInputStream dis = new DataInputStream(bin);
                        final ClassFile file = new ClassFile(dis);
                        Transformer.getManipulator().transformClass(file, d.getDefinitionClass().getClassLoader(), true);
                        String dumpDir = AgentOptions.getOption(AgentOption.DUMP_DIR);
                        if (dumpDir != null) {
                            FileOutputStream s = new FileOutputStream(dumpDir + '/' + d.getDefinitionClass().getName() + "1.class");
                            DataOutputStream dos = new DataOutputStream(s);
                            file.write(dos);
                            dos.flush();
                            dos.close();
                            // s.write(d.getDefinitionClassFile());
                            s.close();
                        }
                    } catch (IOException a) {
                        a.printStackTrace();
                    }
                }
            } catch (Exception ex) {
                ex.printStackTrace();
            }
            throw (new RuntimeException(e));
        }
    }

    public static Instrumentation getInstrumentation() {
        return inst;
    }

    public static Set<Extension> getIntegrationInfo(ClassLoader clr) {
        final ServiceLoader<Extension> loader = ServiceLoader.load(Extension.class, clr);
        final Set<Extension> integrations = new HashSet<Extension>();
        final Iterator<Extension> it = loader.iterator();
        while (it.hasNext()) {
            integrations.add(it.next());
        }
        return integrations;
    }
}
