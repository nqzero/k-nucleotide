/*

 modified by nqzero, offered under the BSD license
 copyright 2019 nqzero, the full notice of which includes this block of text and may not be removed
 the "benchmark" game is terrible for a number of reasons, including:
 - the test harness is difficult to configure and use, making the barrier to optimization abnormally high
 - the tests are not representative of common programming tasks
 - there's no attempt to account for JIT warmup, and many of the tasks are too short to ever warm up
 - the maintainers are opinionated in terms of what code they'll allow, effectively choosing the winners
 - doesn't appear to allow for jvm options to be included
 - the test cpu is from 2007 and is not necessarily representative of current cpus
 this is not a meaningful benchmark in any way and the use of the term should be removed from the game
 https://github.com/nqzero/k-nucleotide

 this software is derived from the benchmark game licensed under the included bsd.txt,
 the original notice of which follows

 The Computer Language Benchmarks Game
 https://salsa.debian.org/benchmarksgame-team/benchmarksgame/
 
 contributed by James McIlree
 modified by Tagir Valeev

*/

import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.AbstractMap.SimpleEntry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * read a fasta file from standard input and calculate statistics
 * note: this code is intended to be run on java 11 with `-Xms2G -Xmx2G`
 */
public class knucleotide {
    static final byte[] codes = { -1, 0, -1, 1, 3, -1, -1, 2 };
    static final char[] nucleotides = { 'A', 'C', 'G', 'T' };
    static int nproc = Runtime.getRuntime().availableProcessors();
    byte [] seq;
    int total;

    static class Result extends Long2IntOpenHashMap {
        int frag;

        Result() { super(1<<10); }
        
        Result reduce(Result map2) {
            map2.forEach((key, value) -> addTo(key, value));
            return this;
        }
        /**
         * Get the long key for given byte array of codes at given offset and length
         * (length must be less than 32)
         */
        static long getKey(byte[] arr, int offset, int length) {
            long key = 0;
            for (int i = offset; i < offset + length; i++) {
                key = (key<<2) + arr[i];
            }
            return key;
        }
        Result create(knucleotide knuc, int offset, int frag) {
            byte [] seq = knuc.seq;
            this.frag = frag;
            int lastIndex = knuc.total - frag + 1;
            for (int index = offset; index < lastIndex; index += frag)
                addTo(getKey(seq, index, frag), 1);
            return this;
        }
        String writeFrequencies(float totalCount) {
            List<java.util.Map.Entry<String, Integer>> freq = new ArrayList<>(size());
            forEach((key, cnt) -> freq.add(new SimpleEntry<>(keyToString(key,frag),cnt)));
            freq.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
            String result = "";
            for (java.util.Map.Entry<String, Integer> entry : freq) {
                result += String.format(Locale.ENGLISH, "%s %.3f\n", entry.getKey(),
                        entry.getValue() * 100.0f / totalCount);
            }
            return result+'\n';
        }
        int get(int frag,long k) {
            return this.frag==frag ? get(k):0;
        }
    }

    ArrayList<Callable<Result>> createFragmentTasks(int[] frags) {
        ArrayList<Callable<Result>> tasks = new ArrayList<>();
        for (int frag : frags)
            for (int index = 0; index < frag; index++) {
                int offset = index;
                tasks.add(() -> new Result().create(this, offset, frag));
            }
        return tasks;
    }



    static String writeCount(List<Future<Result>> futures, String frag) throws Exception {
        byte[] key = toCodes(frag.getBytes(StandardCharsets.ISO_8859_1),frag.length());
        long k = Result.getKey(key, 0, frag.length());
        int count = 0;
        for (Future<Result> future : futures)
            count += future.get().get(frag.length(),k);
        return count + "\t" + frag + '\n';
    }

    /**
     * Convert long key to the nucleotides string
     */
    static String keyToString(long key, int length) {
        char[] res = new char[length];
        for (int i = 0; i < length; i++) {
            res[length - i - 1] = nucleotides[(int) (key & 0x3)];
            key >>= 2;
        }
        return new String(res);
    }


    /**
     * Convert given byte array (limiting to given length) containing acgtACGT
     * to codes (0 = A, 1 = C, 2 = G, 3 = T) and returns new array
     */
    static byte[] toCodes(byte[] sequence, int length) {
        byte[] result = new byte[length];
        for (int i = 0; i < length; i++) {
            result[i] = codes[sequence[i] & 0x7];
        }
        return result;
    }

    
    static final int grt = '>';
    static final int nln = '\n';


    static class Reader {
    
        /** 
         * size of the read buffer, ideally should match OS standard io buffer sizes
         * could use power-of-two growth to adjust dynamically
         * this value appears to be a good compromise
         */
        int blockSize = 1<<20;
        /** current index into the knuc data buffer */
        int ki = 0;
        /** number of elements in the knuc data buffer */
        int kn = 0;
        byte [] raw;
        volatile Wrapper [] wrapped = new Wrapper[1<<16];
        Decoder task = new Decoder();
        Collator task2 = new Collator();
        int kwrap = 0;
        volatile byte [] finish;
        volatile int fsize;
        {
            task.start();
            if (nproc > 3) task2.start();
        }
        
        static void noop() {
            try {
                Thread.sleep(0);
            }
            catch (InterruptedException ex) {}
        }

        class Decoder extends Thread {
            public void run() {
                int kraw = 0;
                for (Wrapper raw; true; kraw++) {
                    while ((raw = wrapped[kraw])==null) noop();
                    if (raw.data==null)
                        break;
                    raw.make();
                }
                if (nproc <= 3) task2.run();
            }
        }
        class Collator extends Thread {
            public void run() {
                int kraw = 0;
                int total = 0;
                byte [] data = new byte[blockSize];
                int num;
                for (Wrapper raw; true; kraw++) {
                    while ((raw = wrapped[kraw])==null || (num = raw.position) < 0) noop();
                    if (raw.data==null)
                        break;
                    if (num+total > data.length) {
                        byte [] next = new byte[data.length*2];
                        System.arraycopy(data,0,next,0,total);
                        data = next;
                    }
                    System.arraycopy(raw.data,0,data,total,num);
                    total += num;
                }
                fsize = total;
                finish = data;
            }
        }
        static class Wrapper {
            byte [] data;
            int start;
            int size;
            volatile int position;
            void build(Reader xx) {
                data = xx.raw;
                start = xx.ki;
                size = xx.kn;
                position = -1;
            }
            int make() {
                int kdata = 0;
                for (int ii=start; ii < size; ii++) {
                    byte val = data[ii];
                    if (val==grt) break;
                    if (val==nln) continue;
                    data[kdata++] = codes[val & 0x7];
                }
                return position = kdata;
            }
        }

        void place() {
            Wrapper payload = new Wrapper();
            payload.build(this);
            wrapped[kwrap++] = payload;
            ki = kn;
        }

        boolean read() throws IOException {
            ki = 0;
            raw = new byte[blockSize];
            kn = System.in.read(raw);
            return kn >= 0;
        }
        
        void read(knucleotide knuc,InputStream is) throws IOException {
            int kc = 0;
            loop:
            while (ki < kn || read())
                while (ki < kn)
                    if (raw[ki++]==grt && ++kc==3) break loop;
            loop:
            while (ki < kn || read()) {
                while (ki < kn)
                    if (raw[ki++]==nln) break loop;
            }
            loop:
            while (ki < kn || read())
                place();
            wrapped[kwrap] = new Wrapper();


            while (finish==null)
                if (nproc <= 3) noop();
            
            knuc.seq = finish;
            knuc.total = fsize;
        }
    }

    public static void main(String[] args) throws Exception {
        Reader reader = new Reader();
        knucleotide knuc = new knucleotide();
        reader.read(knuc,System.in);
        

        ExecutorService pool = Executors.newFixedThreadPool(nproc);
        int[] fragmentLengths = { 1, 2, 3, 4, 6, 12, 18 };
        List<Future<Result>> futures = pool.invokeAll(knuc.createFragmentTasks(fragmentLengths));
        pool.shutdown();

        String result = "";

        result += futures.get(0).get().writeFrequencies(knuc.total);
        result += futures.get(1).get().reduce(futures.get(2).get()).writeFrequencies(knuc.total - 1);

        String[] frags = { "GGT", "GGTA", "GGTATT", "GGTATTTTAATT", "GGTATTTTAATTTATAGT" };
        for (String frag : frags)
            result += writeCount(futures, frag);

        System.out.print(result);
    }
}

/*

	 cp=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/fd/1)
	 alias java="$java11/bin/java -Xms2G -Xmx2G"
	 java -cp target/classes:$cp fasta 1000 > input.txt
	 java -cp target/classes:$cp knucleotide < input.txt | diff - output.txt 
	 java -cp target/classes:$cp fasta 25000000 > t1
	 time java -cp target/classes:$cp knucleotide < t1
	 { for ii in {1..12}; do time java -cp target/classes:$cp knucleotide < t1 > /dev/null; sleep 10; done; } 2> x2

*/

