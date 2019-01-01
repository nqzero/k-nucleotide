# the k-nucleotide frequency counter

this is an implementation that's intended to meet the requirements of the
programming language "benchmark" game's k-nucleotide task

* read a FASTA format file from standard input
* extract the third sequence
* count certain nucleotide sequences
* write results to standard output


## modifications

* architectural improvements: static methods are replaced with classes
* input is read in larger buffers
* parallel processing of input


## results

* running at the bash prompt using the builtin time
* on an i5-3570 on ubuntu 16.04
* the median "user" runtime of 21 runs
* modified: real    0m2.937s
* original: real    0m3.970s
* 35% faster (or a 26% reduction in processing time)

i'm not running on either the official Q6600 cpu or python script

* i don't have access to that cpu (it's from 2007)
* the official script requires extensive customization and that isn't repeatable


## data

|modified |original |
|--------:|--------:|
|2.869s   |3.736s   |
|2.895s   |3.767s   |
|2.895s   |3.779s   |
|2.896s   |3.780s   |
|2.905s   |3.786s   |
|2.913s   |3.788s   |
|2.917s   |3.789s   |
|2.926s   |3.806s   |
|2.931s   |3.808s   |
|2.934s   |3.809s   |
|2.937s   |3.970s   |
|2.954s   |3.973s   |
|2.963s   |3.990s   |
|3.014s   |3.999s   |
|3.087s   |4.010s   |
|3.101s   |4.024s   |
|3.137s   |4.039s   |
|3.156s   |4.040s   |
|3.170s   |4.056s   |
|3.174s   |4.064s   |
|3.175s   |4.088s   | 

## running


```
	 alias java="$java11/bin/java -Xms2G -Xmx2G"
	 cp=$(mvn -q dependency:build-classpath -Dmdep.outputFile=/dev/fd/1)
	 java -cp target/classes:$cp fasta 1000 > input.txt
	 java -cp target/classes:$cp knucleotide < input.txt | diff - output.txt 
	 java -cp target/classes:$cp fasta 25000000 > t1
	 time java -cp target/classes:$cp knucleotide < t1
	 { for ii in {1..12}; do time java -cp target/classes:$cp knucleotide < t1 > /dev/null; sleep 10; done; } 2> x2

	 # compare vs orig
	 rm -Rf v1 v2 w1 w2
	 git checkout orig   && mvn clean compile && mv target/classes v1
         git checkout master && mvn clean compile && mv target/classes v2
         for ii in {1..21}; do for jj in 1 2; \
	     do { time java -cp v$jj:$cp knucleotide < t1 > /dev/null; } 2>&1 | tee -a w$jj; \
	     sleep 10; done; done
```

other versions of java should work as well


## the "benchmark" game

the following verbage is included in the header block of the source code:

 the "benchmark" game is terrible for a number of reasons, including:
 - the test harness is difficult to configure and use, making the barrier to optimization abnormally high
 - the tests are not representative of common programming tasks
 - there's no attempt to account for JIT warmup, and many of the tasks are too short to ever warm up
 - the maintainers are opinionated in terms of what code they'll allow, effectively choosing the winners
 - doesn't appear to allow for jvm options to be included
 - the test cpu is from 2007 and is not necessarily representative of current cpus
this is not a meaningful benchmark in any way and the use of the term should be removed from the game


for that matter, it's not really a game either. and the included code is not of good quality and shouldn't be used as a starting point to solve real problems. due to these flaws, it is intentionally not linked from this document


in particular, it's worth highlighting the flaws in one of the key arguments that the proponents and "maintainers" of this game make. as justification for not properly accounting for startup, the "maintainers" write:

> JVM start-up, JIT, OSR… are quickly effective and typical cold / warmed-up comparison at most of these workloads will show miniscule difference

it is plausible that this statement is true for (some of) the current implementations. but because of this policy, it favors implementations that compile quickly, at the expense of better code that would ultimately be easier to maintain and perform better





## further work

* profile the code
* look at JIT timing and tweak code to encourge compiling
* look at the uniqueness of the hash function
* alternative hashmaps could also be looked at (koloboke would be good for java 8)
* the c# code uses unsafe, which might be useful here too
  * a hashmap impl that uses unsafe to store the key and value at the same location might have value beyond this demo
* break up processing depending on fragment length
  * short fragments should use unity hash
* move executor service into the class and look at startup cost

it's not allowed by the game spec, but for real usage it would be interesting to try a suffix (or other) tree (or trie)


