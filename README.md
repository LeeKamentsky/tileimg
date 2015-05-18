It's a program that uses Bio-Formats to read a TIF stack and cut it up into manageably-sized tiles. Build it using 

`mvn package`

then run it like this:

`java -jar target\tileimg-jar-with-dependencies.jar -?`

It's dumb, but it does the job.
