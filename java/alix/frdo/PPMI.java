package alix.frdo;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import alix.fr.Lexik;
import alix.util.Char;
import alix.util.IntPair;
import alix.util.IntRoller;
import alix.util.SparseMat;
import alix.util.Term;
import alix.util.Top;

/**
 * Efficient sparce matrix
 * @author fred
 *
 */
public class PPMI {
    final int left;
    final int right;
    final int min;
    final int laplace;
    long wc;
    HashMap<String, Integer> byString = new HashMap<String, Integer>();
    String[] byIndex;
    /** is stopwords by index */
    int[] stop;
    final int stoplimit = 500;
    HashMap<String, WCounter> freqs = new HashMap<String, WCounter>(32768);
    SparseMat mat = new SparseMat();
    
    public PPMI(final int left, final int right)
    {
        this.left = left;
        this.right = right;
        this.laplace = 2;
        this.min = 2;
    }
    class WCounter implements Comparable<WCounter>
    {
        private final String label;
        private int count = 1;
        public WCounter(final String label) {
            this.label = label;
        }
        public void inc() {
            count++;
        }
        public int count() {
            return count;
        }
        public String label() {
            return label;
        }
        @Override
        /**
         * Default comparator for term informations,
         */
        public int compareTo(WCounter o)
        {
          return (o.count - count );
        }
    }
    
    public long freqs(String textFile) throws FileNotFoundException, IOException
    {
        Term term = new Term();
        HashMap<String, WCounter> freqs = this.freqs; // direct handler for perfs
        WCounter wcounter;
        String label;
        long wc = this.wc;
        char c;
        int i;
        int chars;
        int bufsize = 1024*1024*64;
        char[] buf = new char[bufsize];
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(textFile), StandardCharsets.UTF_8)) {
            while((chars = reader.read(buf)) > 0) {
                System.out.print('.');
                for (i = 0; i < chars; i++) {
                    c = buf[i];
                    if (Char.isLetter(c) || c == '_') {
                        term.append(c);
                        continue;
                    }
                    if (term.isEmpty()) continue;
                    wc++;
                    wcounter = freqs.get(term);
                    if (wcounter == null) {
                        label = term.toString();
                        freqs.put(label, new WCounter(label));
                    } else {
                        wcounter.inc();
                    }
                    term.reset();
                }
            }
            reader.close();
        }
        System.out.println();
        this.wc = wc;
        return wc;
    }
    
    public void dicSave(String dicFile) throws IOException
    {
        List<WCounter> list = new ArrayList<WCounter>(freqs.values());
        WCounter entry;
        Collections.sort(list);
        int size = list.size();
        try (BufferedWriter writer = Files.newBufferedWriter(Paths.get(dicFile), StandardCharsets.UTF_8)) {
            for (int i=0;i < size; i++) {
                entry = list.get(i);
                writer.write(entry.label()+'\t' + entry.count()+'\n');
            }
            writer.close();
        }
    }
    
    public void dicLoad(String dicFile) throws IOException
    {
        String l;
        int code = 0;
        int min = this.min;
        stop = new int[stoplimit];
        ArrayList<String> list = new ArrayList<String>();
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(dicFile), StandardCharsets.UTF_8)) {
            while((l = reader.readLine()) != null) {
                String[] cells = l.split("\t");
                int count = Integer.parseInt(cells[1]);
                if (count < min) continue;
                byString.put(cells[0], code);
                list.add(cells[0]);
                if (code < stoplimit && Lexik.isStop(cells[0])) stop[code] = 1;
                code++;
            }
        }
        byIndex = list.toArray(new String[list.size()]);
    }
    public void coocs(String textFile) throws IOException
    {
        IntRoller slider = new IntRoller(left, right);
        int size = slider.size();
        for (int i=0; i < size; i++) slider.push(-1);
        Term term = new Term();
        String l;
        char c;
        Integer code;
        IntPair pair = new IntPair();
        HashMap<IntPair, SparseMat.Counter> coocs = mat.hash;
        SparseMat.Counter counter;
        int stoplimit = this.stoplimit;
        int chars;
        int bufsize = 1024*1024*64;
        char[] buf = new char[bufsize];
        try (BufferedReader reader = Files.newBufferedReader(Paths.get(textFile), StandardCharsets.UTF_8)) {
            while((chars = reader.read(buf)) > 0) {
                System.out.print('.');
                for (int i = 0; i < chars; i++) {
                    c = buf[i];
                    if (Char.isLetter(c) || c == '_') {
                        term.append(c);
                        continue;
                    }
                    if (term.isEmpty()) continue;
                    code = byString.get(term);
                    term.reset();
                    if (code == null) slider.push(-1);
                    else slider.push(code);
                    int word = slider.get(0);
                    if (word < 0) continue;
                    // if (word < stoplimit && stop[word] > 0) continue; 
                    pair.x(word);
                    int context;
                    for (int pos = left; pos <= right; pos++) {
                        if (pos == 0) continue;
                        context = slider.get(pos);
                        if (context < 0) continue;
                        pair.y(context);
                        counter = coocs.get(pair);
                        if (counter != null) {
                            counter.inc();
                        }
                        else {
                            coocs.put(new IntPair(word, context), new SparseMat.Counter());
                        }
                    }
                }
            }
            reader.close();
        }
    }
    public static void main(String[] args) throws Exception
    {
        long time;
        time = System.nanoTime();
        PPMI mat1 = new PPMI(-2, +2);
        if (!new File(args[0]).exists()) {
            System.out.println("Extract dic from "+args[1]+" to "+args[0]);
            long wc = mat1.freqs(args[1]);
            System.out.println("Dic " + ((System.nanoTime() - time) / 1000000) + " ms wc="+wc+" dicSize="+mat1.freqs.size());
            mat1.dicSave(args[0]);
        }
        System.out.println("Loadindg dic "+args[0]);
        mat1.dicLoad(args[0]);
        
        // String dstName = new File(src).getName().replaceAll("(.)\\.[^\\.]+$", "$1");
        time = System.nanoTime();
        System.out.println("Build cooccurrences table");
        mat1.coocs(args[1]);
        System.out.println("Coocs table " + ((System.nanoTime() - time) / 1000000) + " ms");
        time = System.nanoTime();
        mat1.mat.compile();
        mat1.mat.coscounts();
        mat1.mat.deviance();
        mat1.mat.ppmi(2);
        System.out.println("Compile dics " + ((System.nanoTime() - time) / 1000000) + " ms");
        BufferedReader keyboard = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        while (true) {
            System.out.println("");
            System.out.print("? ");
            String word = keyboard.readLine().trim();
            word = word.trim();
            if (word.isEmpty()) System.exit(0);
            Integer code = mat1.byString.get(word);
            if (code == null) {
                System.out.println("Mot inconnu");
            }
            /* TODO get vector iterator
            int start = mat1.mat.rowsIndex[code];
            int end = mat1.mat.rowsIndex[code + 1];
            int[] rows = mat1.mat.rows;
            int[] cols = mat1.mat.cols;
            int[] counts = mat1.mat.counts;
            for(int i = start; i < end; i++) {
                System.out.print(mat1.byIndex[rows[i]]);
                System.out.print(" ");
                System.out.print(mat1.byIndex[cols[i]]);
                System.out.print(": ");
                System.out.println(counts[i]);
            }
            */
            Top<Integer> top;
            System.out.println("--------------------");
            System.out.println(word+" LABBE");
            top = mat1.mat.topLabbe(code, 40);
            for(Top.Entry<Integer> entry: top) {
                System.out.println(mat1.byIndex[entry.value()]+"\t"+entry.score());
            }
            System.out.println("--------------------");
            top = mat1.mat.cosine(code, SparseMat.COSCOUNTS, 40);
            for(Top.Entry<Integer> entry: top) {
                System.out.println(mat1.byIndex[entry.value()]+"\t"+entry.score());
            }
            System.out.println("--------------------");
            System.out.println(word+" PPMI");
            top = mat1.mat.cosine(code, SparseMat.PPMI, 40);
            for(Top.Entry<Integer> entry: top) {
                System.out.println(mat1.byIndex[entry.value()]+"\t"+entry.score());
            }
        }
    }
}