/*
 * This file is part of GumTree.
 *
 * GumTree is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * GumTree is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with GumTree.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2020 Jean-Rémy Falleri <jr.falleri@gmail.com>
 */

package com.github.gumtree.benchmark;

import com.github.gumtreediff.actions.EditScript;
import com.github.gumtreediff.actions.EditScriptGenerator;
import com.github.gumtreediff.actions.SimplifiedChawatheScriptGenerator;
import com.github.gumtreediff.actions.model.*;
import com.github.gumtreediff.gen.Register;
import com.github.gumtreediff.gen.SyntaxException;
import com.github.gumtreediff.gen.TreeGenerators;
import com.github.gumtreediff.gen.jdt.JdtTreeGenerator;
import com.github.gumtreediff.gen.python.PythonTreeGenerator;
import com.github.gumtreediff.gen.treesitter.PythonTreeSitterTreeGenerator;
import com.github.gumtreediff.gen.srcml.SrcmlCppTreeGenerator;
import com.github.gumtreediff.io.DirectoryComparator;
import com.github.gumtreediff.matchers.*;
import com.github.gumtreediff.matchers.CompositeMatchers.ClassicGumtree;
import com.github.gumtreediff.matchers.CompositeMatchers.ClassicGumtreeTheta;
import com.github.gumtreediff.matchers.CompositeMatchers.HybridGumtree;
import com.github.gumtreediff.matchers.CompositeMatchers.XyMatcher;
import com.github.gumtreediff.matchers.heuristic.LcsMatcher;
import com.github.gumtreediff.matchers.optimal.rted.RtedMatcher;
import com.github.gumtreediff.matchers.optimal.zs.ZsMatcher;
import com.github.gumtreediff.tree.TreeContext;
import com.github.gumtreediff.utils.Pair;


import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

public class RunOnDataset {
    private static final int TIME_MEASURES = 1;
    private static final int TIMEOUT = 5;
    private static String ROOT_FOLDER;
    private static FileWriter OUTPUT;
    private static final List<MatcherConfig> configurations = new ArrayList<>();
    private static long setupTime;

    public static void main(String[] args) throws IOException, ClassNotFoundException {
        long start = System.nanoTime();
        if (args.length < 2) {
            System.err.println(args.length);
            System.err.println("Wrong command. Expected arguments: INPUT_FOLDER OUTPUT_FILE. Got: "
                    + Arrays.toString(args));
            System.exit(1);
        }
        ROOT_FOLDER = new File(args[0]).getAbsolutePath();
        TreeGenerators.getInstance().install(
                JdtTreeGenerator.class, JdtTreeGenerator.class.getAnnotation(Register.class));
        /*
        TreeGenerators.getInstance().install(
                PythonTreeGenerator.class, PythonTreeGenerator.class.getAnnotation(Register.class));
         */
        TreeGenerators.getInstance().install(
                PythonTreeSitterTreeGenerator.class, PythonTreeSitterTreeGenerator.class.getAnnotation(Register.class));
        TreeGenerators.getInstance().install(
                SrcmlCppTreeGenerator.class, SrcmlCppTreeGenerator.class.getAnnotation(Register.class));
        OUTPUT = new FileWriter(args[1]);

        String header = "case;algorithm;" + "t;".repeat(TIME_MEASURES) + "pt;st;s;ni;nd;nu;nm";
        OUTPUT.append(header + "\n");

        for (int i = 2; i < args.length; i++) {
            Class<? extends Matcher> matcherClass = (Class<? extends Matcher>) Class.forName(args[i]);
            configurations.add(new MatcherConfig(matcherClass.getSimpleName(), () -> {
                try {
                    Constructor<? extends Matcher> matcherConstructor = matcherClass.getConstructor();
                    return matcherConstructor.newInstance();
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }));
        }

        if (configurations.isEmpty()) {
            configurations.add(new MatcherConfig("simple", CompositeMatchers.SimpleGumtree::new));
            configurations.add(new MatcherConfig("hybrid-20", CompositeMatchers.HybridGumtree::new, smallBuMinsize()));
            configurations.add(new MatcherConfig("opt-20", CompositeMatchers.ClassicGumtree::new, smallBuMinsize()));
            configurations.add(new MatcherConfig("opt-200", CompositeMatchers.ClassicGumtree::new, largeBuMinsize()));
            // configurations.add(new MatcherConfig("xy", CompositeMatchers.XyMatcher::new));
            // configurations.add(new MatcherConfig("theta", CompositeMatchers.Theta::new));
            // configurations.add(new MatcherConfig("lcs", LcsMatcher::new));
            // configurations.add(new MatcherConfig("cd-theta", CompositeMatchers.ChangeDistillerTheta::new));
            // configurations.add(new MatcherConfig("classic-theta", CompositeMatchers.ClassicGumtreeTheta::new));
            
            // VERY SLOW
            // configurations.add(new MatcherConfig("cd", CompositeMatchers.ChangeDistiller::new));
            // configurations.add(new MatcherConfig("zs", ZsMatcher::new));
            

            // OOM
            // configurations.add(new MatcherConfig("rted-theta", CompositeMatchers.RtedTheta::new));
        }
        setupTime = System.nanoTime() - start;

        DirectoryComparator comparator = new DirectoryComparator(args[0] + "/before", args[0] + "/after");
        comparator.compare();
        int done = 0;
        int size = comparator.getModifiedFiles().size();
        for (Pair<File, File> pair : comparator.getModifiedFiles()) {
            int pct = (int) (((float) done / (float) size) * 100);
            System.out.printf("\r%s %s%% %s/%s   Done", displayBar(pct), pct, done, size);
            try {
                handleCase(pair.first, pair.second);
            }
            catch (SyntaxException e) {
                System.out.println("Problem parsing " + pair.first.getPath());
            }
            OUTPUT.flush();
            done++;
        }
        OUTPUT.close();
        System.out.println("\nCompleted");
    }

    private static void handleCase(File src, File dst) throws IOException {
        long startedTime = System.nanoTime();
        TreeContext srcT;
        TreeContext dstT;
        try{
            srcT = TreeGenerators.getInstance().getTree(src.getAbsolutePath());
            dstT = TreeGenerators.getInstance().getTree(dst.getAbsolutePath());
        } catch (OutOfMemoryError e) {
            System.out.println("Out of memory parsing " + src.getAbsolutePath().substring(ROOT_FOLDER.length() + 1));
            return;
        }
        long parsingTime = System.nanoTime() - startedTime;
        System.out.println("Parsed " + src.getAbsolutePath().substring(ROOT_FOLDER.length() + 1) + " in " + parsingTime/1000000000 + "s");
        for (MatcherConfig config : configurations) {
            Matcher m = config.instantiate();
            handleMatcher(src.getAbsolutePath().substring(ROOT_FOLDER.length() + 1),
                    config.name, m, srcT, dstT, parsingTime);
        }
    }

    private static void shutdownAndAwaitTermination(ExecutorService pool) {
    // Disable new tasks from being submitted
    pool.shutdown();
    try {
        // Wait a while for existing tasks to terminate
        if (!pool.awaitTermination(60, TimeUnit.SECONDS)) {
            // Cancel currently executing tasks forcefully
            pool.shutdownNow();
            // Wait a while for tasks to respond to being cancelled
            if (!pool.awaitTermination(60, TimeUnit.SECONDS))
                System.err.println("Pool did not terminate");
        }
    } catch (InterruptedException ex) {
        // (Re-)Cancel if current thread also interrupted
        pool.shutdownNow();
        // Preserve interrupt status
        Thread.currentThread().interrupt();
    }
}

    private static void handleMatcher(String file, String matcher, Matcher m,
            TreeContext src, TreeContext dst, long parsingTime) throws IOException {
        // System.out.println("Matching " + file + " with " + matcher);
        long[] times = new long[TIME_MEASURES];
        MappingStore mappings = null;
        Boolean oom = false;
        Boolean timeout = false;
        ExecutorService executor = Executors.newSingleThreadExecutor();

        // First run is always the slowest by a large margin, the following runs are roughly the same and much faster
        // We care about the 1 off scenario, so we only take the first run
        for (int i = 0; i < TIME_MEASURES; i++) {
            long startedTime = System.nanoTime();

            Future<?> future = executor.submit(() -> {
                try {
                    return m.match(src.getRoot(), dst.getRoot());
                } catch (OutOfMemoryError e) {
                    throw e;
                }
            });

            try {
                mappings = (MappingStore) future.get(TIMEOUT, TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                if (e.getCause() instanceof OutOfMemoryError) {
                    System.err.println("Out of memory for " + file + " with " + matcher);
                    oom = true;
                    break;
                } else {
                    throw new RuntimeException(e);
                }
            } catch (TimeoutException e) {
                System.err.println("Timeout for " + file + " with " + matcher);
                // future.cancel(true);
                timeout = true;
                break;
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            } finally {
                long elapsedTime = System.nanoTime() - startedTime;
                times[i] = elapsedTime;
            }
        }
        executor.shutdownNow();
        // shutdownAndAwaitTermination(executor);
        Arrays.sort(times);

        if (oom || timeout) {
            String error = oom ? "OOM" : "TIMEOUT";
            OUTPUT.append(file + ";");
            OUTPUT.append(matcher + ";");
            for (int i = 0; i < TIME_MEASURES; i++) {
                OUTPUT.append(times[i] + ";");
            }
            OUTPUT.append(parsingTime + ";");
            OUTPUT.append(setupTime + ";");
            OUTPUT.append(error + ";" + error + ";" + error + ";" + error + ";" + error +  "\n");
            return;
        }

        EditScriptGenerator g = new SimplifiedChawatheScriptGenerator();
        EditScript s = g.computeActions(mappings);

        int nbIns = 0;
        int nbDel = 0;
        int nbMov = 0;
        int nbUpd = 0;
        for (Action a : s) {
            if (a instanceof Insert)
                nbIns++;
            else if (a instanceof Delete)
                nbDel++;
            else if (a instanceof Update)
                nbUpd++;
            else if (a instanceof Move)
                nbMov += a.getNode().getMetrics().size;
            else if (a instanceof TreeInsert)
                nbIns += a.getNode().getMetrics().size;
            else if (a instanceof TreeDelete)
                nbDel += a.getNode().getMetrics().size;
        }

        OUTPUT.append(file + ";");
        OUTPUT.append(matcher + ";");
        for (int i = 0; i < TIME_MEASURES; i++)
            OUTPUT.append(times[i] + ";");
        OUTPUT.append(parsingTime + ";");
        OUTPUT.append(setupTime + ";");
        int size = s.size();
        OUTPUT.append(size + ";");
        OUTPUT.append(nbIns + ";");
        OUTPUT.append(nbDel + ";");
        OUTPUT.append(nbUpd + ";");
        OUTPUT.append(nbMov + "\n");
    }

    private static class MatcherConfig {
        public final String name;
        private final Supplier<Matcher> matcherFactory;
        private final GumtreeProperties props;

        public MatcherConfig(String name, Supplier<Matcher> matcherFactory, GumtreeProperties props) {
            this.name = name;
            this.matcherFactory = matcherFactory;
            this.props = props;
        }

        public MatcherConfig(String name, Supplier<Matcher> matcherFactory) {
            this.name = name;
            this.matcherFactory = matcherFactory;
            this.props = new GumtreeProperties();
        }

        public Matcher instantiate() {
            Matcher m = matcherFactory.get();
            m.configure(props);
            return m;
        }
    }

    private static GumtreeProperties smallBuMinsize() {
        GumtreeProperties props = new GumtreeProperties();
        props.put(ConfigurationOptions.bu_minsize, 20);
        return props;
    }

    private static GumtreeProperties largeBuMinsize() {
        GumtreeProperties props = new GumtreeProperties();
        props.put(ConfigurationOptions.bu_minsize, 200);
        return props;
    }

    private static String displayBar(int i) {
        StringBuilder sb = new StringBuilder();

        int x = i / 2;
        sb.append("|");
        for (int k = 0; k < 50; k++)
            sb.append(String.format("%s", ((x <= k) ? " " : "=")));
        sb.append("|");

        return sb.toString();
    }
}

