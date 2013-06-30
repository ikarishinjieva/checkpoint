checkpoint
==========

Implementation of simple distributed lock based on Zookeeper, it's used to test multi-process/multi-computer program : ask the multi process stop at certain point, assert, then let them go on.

HOWTO use it
==========
We have a tester and two testee (The testee may be different thread/process or cross machine). We will ask the testee stop at same checkpoint, tester check status at the checkpoint, and testee then go on.

1.  init tester
    ```java
    // start a server to coordinate tester & testee
    Checkpoint.startServer(3333);
    // wait until server start
    Thread.sleep(2000);
    // init a client, to send command or receive status from server
    Checkpoint.init("192.168.1.106:3333");
    ```

2.  init testee
    ```java
    // init a client
    Checkpoint.init("192.168.1.106:3333");
    ```

3.  add checkpoint @ tester
    ```java
    // add a checkpoint, named "some_checkpoint", whose expected kick count is 2
    Checkpoint.add("some_checkpoint", 2);
    ```

4.  kick checkpoint @ both testee
    ```java
    Checkpoint.kick("some_checkpoint");
    ```

5.  hold checkpoint @ tester
    ```java
    Checkpoint.hold("some_checkpoint");
    ```

After the steps, when testee kick the checkpoint, testee will stop and wait for tester notification (Cont).

When tester hold the checkpoint, tester will stop until testee kick the checkpoint (in this case, there should be 2 testee kick before the tester continue).

Then tester could check testee status, make some assertion, with the situation that all testee are stopped at the checkpoint.

At last, tester could call Checkpoint.cont("some_checkpoint") to let testee go on.

What if we don't want to stop testee, in maybe production environment
==========
Please do not init Checkpoint or do not add checkpoint, then Kick will not pause the process.