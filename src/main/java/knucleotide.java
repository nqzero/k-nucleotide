/* The Computer Language Benchmarks Game
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

public class knucleotide {
    static final byte[] codes = { -1, 0, -1, 1, 3, -1, -1, 2 };
    static final char[] nucleotides = { 'A', 'C', 'G', 'T' };

    static class Result extends Long2IntOpenHashMap {
        int frag;
    
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
        Result create(byte[] seq, int offset, int frag) {
            this.frag = frag;
            int lastIndex = seq.length - frag + 1;
            for (int index = offset; index < lastIndex; index += frag)
                addTo(getKey(seq, index, frag), 1);
            return this;
        }
        String writeFrequencies(float totalCount) {
            List<java.util.Map.Entry<String, Integer>> freq = new ArrayList<>(size());
            forEach((key, cnt) -> freq.add(new SimpleEntry<>(keyToString(key,frag),cnt)));
            freq.sort(Map.Entry.comparingByValue(Comparator.reverseOrder()));
            StringBuilder sb = new StringBuilder();
            for (java.util.Map.Entry<String, Integer> entry : freq) {
                sb.append(String.format(Locale.ENGLISH, "%s %.3f\n", entry.getKey(),
                        entry.getValue() * 100.0f / totalCount));
            }
            return sb.append('\n').toString();
        }
        int get(int frag,long k) {
            return this.frag==frag ? get(k):0;
        }
    }

    static ArrayList<Callable<Result>> createFragmentTasks(final byte[] seq,int[] frags) {
        ArrayList<Callable<Result>> tasks = new ArrayList<>();
        for (int frag : frags)
            for (int index = 0; index < frag; index++) {
                int offset = index;
                tasks.add(() -> new Result().create(seq, offset, frag));
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


    static class Reader {
    
        int size = 1<<20;
        byte [][] data = new byte[1<<20][];
        int ndata = 0;
        byte [] alloc() { return data[ndata++] = new byte[size]; }
        { alloc(); }
        
        

        
        byte[] read(InputStream is) throws IOException {
            byte[] bytes = data[0];
            int position = 0;

            int ki = 0;
            int kc = 0;
            int kn = 0;
            byte [] raw = new byte[1<<20];
            loop:
            while ((kn = is.read(raw)) > 0)
                for (ki=0; ki < kn;)
                    if (raw[ki++]=='>' && ++kc==3) break loop;
            loop:
            while (ki < kn || (kn = is.read(raw))+(ki=0) > 0) {
                while (ki < kn)
                    if (raw[ki++]=='\n') break loop;
            }
            loop:
            while (ki < kn || (kn = is.read(raw))+(ki=0) > 0)
                for (; ki < kn; ki++) {
                    byte val = raw[ki];
                    if (val=='>') break loop;
                    if (val=='\n') continue;
                    if (position==size) {
                        position = 0;
                        bytes = alloc();
                    }
                    byte result = codes[val & 0x7];
                    bytes[position++] = result;
                }

            byte [] done = new byte[ndata*size-size+position];
            for (int ii=0; ii < ndata; ii++)
                System.arraycopy(data[ii], 0, done, ii*size, ii==ndata-1 ? position:size);

            return done;
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] sequence = new Reader().read(System.in);

        ExecutorService pool = Executors.newFixedThreadPool(Runtime.getRuntime()
                .availableProcessors());
        int[] fragmentLengths = { 1, 2, 3, 4, 6, 12, 18 };
        List<Future<Result>> futures = pool.invokeAll(createFragmentTasks(sequence,
                fragmentLengths));
        pool.shutdown();

        StringBuilder sb = new StringBuilder();

        sb.append(futures.get(0).get().writeFrequencies(sequence.length));
        sb.append(futures.get(1).get().reduce(futures.get(2).get()).writeFrequencies(sequence.length - 1));

        String[] frags = { "GGT", "GGTA", "GGTATT", "GGTATTTTAATT", "GGTATTTTAATTTATAGT" };
        for (String frag : frags)
            sb.append(writeCount(futures, frag));

        System.out.print(sb);
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

