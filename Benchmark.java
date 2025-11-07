import java.util.concurrent.*;
import java.util.*;
import java.io.*;
import java.util.stream.Collectors;

public class Benchmark {

    static void workload(long iterations) {
        double x = 0.0;
        for (long i = 1; i <= iterations; i++) {
            x += Math.sin(i) * Math.tan((i % 10) + 1);
            if (i % 1000000 == 0) {
                double dummy = x;
            }
        }
        if (Double.isNaN(x)) {
            System.out.println("NaN guard");
        }
    }

    static void warmup() {
        System.out.println("Warm-up: aquecendo JVM/JIT...");
        for (int i = 0; i < 4; i++) {
            workload(100_000);
        }
        System.out.println("Warm-up concluído.");
    }

    static long runNM(int numTasks, int poolSize, long iterationsPerTask) throws InterruptedException {
        ExecutorService pool = Executors.newFixedThreadPool(poolSize);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numTasks);

        for (int t = 0; t < numTasks; t++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    workload(iterationsPerTask);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        Thread.sleep(100);

        long start = System.nanoTime();
        startLatch.countDown();
        doneLatch.await();
        long end = System.nanoTime();
        pool.shutdownNow();
        return TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    static long runOneToOne(int numTasks, long iterationsPerTask) throws InterruptedException {
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(numTasks);
        Thread[] threads = new Thread[numTasks];

        for (int t = 0; t < numTasks; t++) {
            threads[t] = new Thread(() -> {
                try {
                    startLatch.await();
                    workload(iterationsPerTask);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    doneLatch.countDown();
                }
            }, "worker-" + t);
            threads[t].start();
        }

        Thread.sleep(50);

        long start = System.nanoTime();
        startLatch.countDown();
        doneLatch.await();
        long end = System.nanoTime();

        return TimeUnit.NANOSECONDS.toMillis(end - start);
    }

    public static void main(String[] args) throws Exception {
        long iterationsPerTask = 1_000_000L;
        int poolSize = Runtime.getRuntime().availableProcessors();
        int[] testNs = new int[] {10, 100, 500, 1000};

        if (args.length >= 1) {
            iterationsPerTask = Long.parseLong(args[0]);
        }
        if (args.length >= 2) {
            poolSize = Integer.parseInt(args[1]);
        }
        if (args.length >= 3) {
            String[] parts = args[2].split(",");
            testNs = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                testNs[i] = Integer.parseInt(parts[i].trim());
            }
        }

        System.out.println("==== Benchmark N:M vs 1:1 ====");
        System.out.println("Iterações por tarefa: " + iterationsPerTask);
        System.out.println("Pool size (M) para N:M: " + poolSize);
        System.out.println("Testando N = " + Arrays.toString(testNs));
        System.out.println("CPU cores (availableProcessors): " + Runtime.getRuntime().availableProcessors());
        System.out.println();

        warmup();

        File csv = new File("results.csv");
        try (PrintWriter pw = new PrintWriter(new FileWriter(csv))) {
            pw.println("model,poolSize,numTasks,iterationsPerTask,timeMs");

            for (int N : testNs) {
                int repetitions = 3;
                for (int rep = 1; rep <= repetitions; rep++) {
                    System.out.println(String.format("Executando N:M (N=%d, M=%d) rep %d/%d ...", N, poolSize, rep, repetitions));
                    long timeNm = runNM(N, poolSize, iterationsPerTask);
                    System.out.println(" -> N:M time = " + timeNm + " ms");
                    pw.println(String.format("NM,%d,%d,%d,%d", poolSize, N, iterationsPerTask, timeNm));

                    System.out.println(String.format("Executando 1:1 (N=%d) rep %d/%d ...", N, rep, repetitions));
                    long time11 = runOneToOne(N, iterationsPerTask);
                    System.out.println(" -> 1:1 time = " + time11 + " ms");
                    pw.println(String.format("1to1,%d,%d,%d,%d", -1, N, iterationsPerTask, time11));
                    System.out.println();
                    Thread.sleep(200);
                }
            }
            System.out.println("Resultados gravados em: " + csv.getAbsolutePath());
        }
    }
}
