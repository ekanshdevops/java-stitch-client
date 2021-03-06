Java Stitch Client
==================

Quick Start
-----------

This will get you started sending records to Stitch.

### Building a Client

Use
[StitchClientBuilder](src/main/java/com/stitchdata/client/StitchClientBuilder.java)
to build a
[StitchClient](src/main/java/com/stitchdata/client/StitchClient.java). You'll
need to set your client id, authentication token, and namespace. You
should have gotten these when you set up the integration at
http://stitchdata.com. You should close the client when you're done
with it to ensure that all messages are delivered, so we recommend
opening it in a try-with-resources statement.

```java
try (StitchClient stitch = new StitchClientBuilder()
     .withClientId(yourClientId)
     .withToken(yourToken)
     .withNamespace(yourNamespace)
     .build())
{
  // ...
}
```

### Building a Message

You can build a Stitch message by creating a new instance of
[StitchMessage](src/main/java/com/stitchdata/client/StitchMessage.java)
and then calling methods on it to set the properties of the
message. For example:

```java
StitchMessage message = StitchMessage.newUpsert()
    .withTableName("my_table")
    .withKeyNames("id")
    .withSequence(System.currentTimeMillis())
    .withData(data);
```

* Table name is the name of the table you want to load into
* Key names is the list of primary key columns for that table
* Sequence is any arbitrary increasing number used to determine order of updates
* Data is the payload

Data must be a map that conforms to the following rules:

* All keys are strings
* All values are one of:
  * Number (Long, Integer, Short, Byte, Double, Float, BigInteger, BigDecimal)
  * String
  * Boolean
  * Date
  * Map (with string keys and values that conform to these rules)
  * Lists (of objects that conform to these rules)
* It must have a non-null value for each of the keys you specified as "key names"

### Sending Messages

You send a message to Stitch by calling the `push` method on your
`StitchClient` instance, and passing in a `StitchMessage`.

```java
stitch.push(message);
```

Running the Example Program
---------------------------

Please see
[SimpleExample.java](src/main/java/com/stitchdata/client/SimpleExample.java)
for a full working example. You can run this program with your own
credentials by executing this command (replacing CLIENT_ID, TOKEN, and
NAMESPACE with your own values):

```bash
mvn exec:java -Dexec.mainClass=com.stitchdata.client.examples.SimpleExample -Dexec.args="CLIENT_ID TOKEN NAMESPACE"
```

On a successful run, you'll see a "BUILD SUCCESSFUL" message. You
should then wait a few minutes and check your data warehouse, and you
should see the example records.

Advanced Topics
---------------

### Setting message defaults on the client

In a typical use case, several of the fields will be the same for all
messages that you send using a single client. To make this use case
more convenient, you can set some of those fields on the client using
`StitchClientBuilder`. The resulting client will inject the values for
those fields into every message it sends.

```java
StitchClient stitch = new StitchClientBuilder()
    .withClientId(yourClientId)
    .withToken(yourToken)
    .withNamespace(yourNamespace)
    .withTableName("events")
    .withKeyNames("id")
    .build();

// I can omit the table name and key names:
StitchMessage message = new StitchMessage()
    .withAction(Action.UPSERT)
    .withSequence(System.currentTimeMillis())
    .withData(data);
```

### Tuning Buffer Parameters

By default `stitchClient.push()` will accumulate messages locally in a
batch, and then deliver the batch when one of the following conditions
is met:

* The batch has 4 Mb of data
* The batch has 10,000 records
* A minute has passed since the last batch was sent.

If you want to send data more frequently, you can lower the buffer
capacity or the time limit.

```java
StitchClient stitch = new StitchClientBuilder()
    .withClientId(yourClientId)
    .withToken(yourToken)
    .withNamespace(yourNamespace)

    // Flush at 1Mb
    .withBatchSize(1000000)

    // Flush after 1 minute
    .withBatchDelayMillis(10000)
  .build();
```

Setting the batch size to 0 bytes will effectively turn off batching
and force `push` to send a batch of one record with every call. This
is not generally recommended, as batching will give better
performance, but can be useful for low-volume streams or for
debugging.

There is no value in setting a buffer capacity higher than 4 Mb, since
that is the maximum message size Stitch will accept. If you set it to
a value higher than that, you will use more memory, but StitchClient
will deliver the messages in batches no larger than 4 Mb anyway.

Asynchronous Usage
------------------

It is safe for multiple threads to call `push` on a single instance of
`StitchClient`. If buffering is enabled (which it is by default), then
multiple threads will accumulate records into the same batch. When one
of those threads makes a call to `push` that causes the buffer to fill
up, that thread will deliver the entire batch to Stitch. This behavior
should be suitable for many applications. However, if you do not want
records from multiple threads to be sent on the same batch, or if you
want to ensure that a record is only delivered by the thread that
produced it, then you can create a separate StitchClient for each thread.

Developers
----------

In order to deploy snapshots or release artifacts you'll need GPG keys
and a login for the Sonatype servers. You'll want to put something
like the following in your `~/.m2/settings.xml` file.

```xml
<settings>
  <profiles>
    <profile>
      <id>gpg</id>
      <properties>
        <gpg.executable>gpg2</gpg.executable>
        <gpg.keyname>Your GPG keyname</gpg.keyname>
      </properties>
    </profile>
  </profiles>
  <activeProfiles>
    <activeProfile>gpg</activeProfile>
  </activeProfiles>
 <servers>
    <server>
      <id>ossrh</id>
      <username>user</username>
      <password>pass</password>
    </server>
  </servers>
</settings>

Note that in order to sign jars you'll need to set the `gpg.keyname`
if you have multiple keys in your keyring, and you'll need to set
`gpg.passphrase`. In order to deploy to the Sonatype's maven
repository you'll need to enter your credentials under the "servers"
section.

```

### Releasing

1. Decide what version you want to release and make sure that the
version number specified in `pom.xml` is `major.minor.patch-SNAPSHOT`.
2. Before releasing, you should make sure that you're on the master
  branch and that your git repository is clean and up-to-date with
  Github.
3. Make sure gpg-agent is running.
4. [optional] Run `mvn release:clean` to get rid of any artifacts that
  are leftover from a previous release.
5. Run `mvn release:prepare` and hit Enter through the prompts it gives you.
6. Run `mvn release:perform`.

License
-------

Copyright © 2016 Stitch

Distributed under the Apache License Version 2.0
