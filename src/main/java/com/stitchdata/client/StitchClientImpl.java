package com.stitchdata.client;

import com.stitchdata.client.*;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.UnsupportedEncodingException;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import com.cognitect.transit.Writer;
import com.cognitect.transit.TransitFactory;
import com.cognitect.transit.Reader;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.fluent.Response;
import org.apache.http.client.ClientProtocolException;
import org.apache.http.entity.ContentType;
import org.apache.http.StatusLine;
import org.apache.http.HttpResponse;
import org.apache.http.HttpEntity;

import javax.json.Json;
import javax.json.JsonReader;

public class StitchClientImpl implements StitchClient {

    private static final int CAPACITY = 10000;

    private static final ContentType CONTENT_TYPE = ContentType.create("application/transit+json");

    public static final int HTTP_CONNECT_TIMEOUT = 1000 * 60 * 2;
    private final int connectTimeout = HTTP_CONNECT_TIMEOUT;

    private final String stitchUrl;
    private final int clientId;
    private final String token;
    private final String namespace;
    private final String tableName;
    private final List<String> keyNames;
    private final int maxFlushIntervalMillis;
    private final int maxBytes;
    private final int maxRecords;
    private final ResponseHandler responseHandler;
    private final BlockingQueue<MessageWrapper> queue = new ArrayBlockingQueue<MessageWrapper>(CAPACITY);
    private ArrayList<MessageWrapper> items = new ArrayList<MessageWrapper>();
    private int numBytes = 0;
    private final CountDownLatch closeLatch;

    private long lastFlushTime = System.currentTimeMillis();

    private class MessageWrapper {
        byte[] bytes;
        boolean isEndOfStream;
        ResponseHandler responseHandler;
        MessageWrapper(byte[] bytes, boolean isEndOfStream, ResponseHandler responseHandler) {
            this.bytes = bytes;
            this.isEndOfStream = isEndOfStream;
            this.responseHandler = responseHandler;
        }
    }

    private class Worker implements Runnable {

        public boolean shouldFlush() {
            return
                numBytes >= maxBytes ||
                items.size() >= maxRecords ||
                (System.currentTimeMillis() - lastFlushTime ) >= maxFlushIntervalMillis;
        }

        private void flush() {
            ArrayList<Map> messages = new ArrayList<Map>(items.size());
            for (MessageWrapper item : items) {
                ByteArrayInputStream bais = new ByteArrayInputStream(item.bytes);
                Reader reader = TransitFactory.reader(TransitFactory.Format.JSON, bais);
                messages.add((Map)reader.read());
            }

            try {
                StitchResponse response = pushImpl(messages);
                for (int i = 0; i < items.size(); i++) {
                    ResponseHandler handler = items.get(i).responseHandler;
                    if (handler != null) {
                        handler.handleOk(messages.get(i), response);
                    }
                }
            } catch (Exception e) {
                for (int i = 0; i < items.size(); i++) {
                    ResponseHandler handler = items.get(i).responseHandler;
                    if (handler != null) {
                        handler.handleError(messages.get(i), e);
                    }
                }
            }

            items.clear();
            numBytes = 0;
            lastFlushTime = System.currentTimeMillis();
        }

        public void run() {
            boolean running = true;
            while (running) {
                MessageWrapper item;
                try {
                    item = queue.take();
                } catch (InterruptedException e) {
                    return;
                }
                if (item.isEndOfStream) {
                    running = false;
                    flush();
                    closeLatch.countDown();
                }

                else {
                    items.add(item);
                    numBytes += item.bytes.length;
                    if (shouldFlush()) {
                        flush();
                    }
                }
            }
        }
    }

    private MessageWrapper wrap(Map message, ResponseHandler responseHandler) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Writer writer = TransitFactory.writer(TransitFactory.Format.JSON, baos);
        // using bytes to avoid storing a mutable map
        writer.write(message);
        return new MessageWrapper(baos.toByteArray(), false, responseHandler);
    }

    StitchClientImpl(String stitchUrl, int clientId, String token, String namespace, String tableName, List<String> keyNames,
                 int maxFlushIntervalMillis, int maxBytes, int maxRecords, ResponseHandler responseHandler) {
        this.stitchUrl = stitchUrl;
        this.clientId = clientId;
        this.token = token;
        this.namespace = namespace;
        this.tableName = tableName;
        this.keyNames = keyNames;
        this.maxFlushIntervalMillis = maxFlushIntervalMillis;
        this.maxBytes = maxBytes;
        this.maxRecords = maxRecords;
        this.closeLatch = new CountDownLatch(1);
        this.responseHandler = responseHandler;
        Thread workerThread = new Thread(new Worker());
        workerThread.start();
    }

    /**
     * Push a list of messages, blocking until Stitch accepts them.
     *
     * @param messages List of messages to send. Use
     *                 client.newUpsertMessage() to create messages.
     */
    public StitchResponse pushImpl(List<Map> messages) throws StitchException, IOException {
        // TODO: DOn't mutate
        for (Map message : messages) {
            message.put(Field.CLIENT_ID, clientId);
            message.put(Field.NAMESPACE, namespace);
            if (tableName != null && !message.containsKey(Field.TABLE_NAME)) {
                message.put(Field.TABLE_NAME, tableName);
            }
            if (keyNames != null && !message.containsKey(Field.KEY_NAMES)) {
                message.put(Field.KEY_NAMES, keyNames);
            }
        }

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        Writer writer = TransitFactory.writer(TransitFactory.Format.JSON, baos);
        writer.write(messages);
        String body = baos.toString("UTF-8");

        try {
            Request request = Request.Post(stitchUrl)
                .connectTimeout(connectTimeout)
                .addHeader("Authorization", "Bearer " + token)
                .bodyString(body, CONTENT_TYPE);

            HttpResponse response = request.execute().returnResponse();

            StatusLine statusLine = response.getStatusLine();
            HttpEntity entity = response.getEntity();
            JsonReader rdr = Json.createReader(entity.getContent());
            StitchResponse stitchResponse = new StitchResponse(
                statusLine.getStatusCode(),
                statusLine.getReasonPhrase(),
                rdr.readObject());
            if (stitchResponse.isOk()) {
                return stitchResponse;
            }
            else {
                throw new StitchException(stitchResponse);
            }
        } catch (ClientProtocolException e) {
            throw new RuntimeException(e);
        }
    }

    public StitchResponse push(List<Map> messages) throws StitchException, IOException {
        List<Map> copy = new ArrayList<Map>();
        for (Map message : messages) {
            copy.add(new HashMap(message));
        }
        return pushImpl(copy);
    }

    public StitchResponse push(Map message) throws StitchException, IOException {
        ArrayList<Map> messages = new ArrayList<Map>();
        messages.add(message);
        return push(messages);
    }

    public boolean offer(Map m, ResponseHandler responseHandler) {
        return queue.offer(wrap(m, responseHandler));
    }

    public boolean offer(Map m, ResponseHandler responseHandler, long timeout, TimeUnit unit) throws InterruptedException {
        return queue.offer(wrap(m, responseHandler), timeout, unit);
    }

    public void put(Map m, ResponseHandler responseHandler) throws InterruptedException {
        queue.put(wrap(m, responseHandler));
    }

    public void validate(Map message) {
        // TODO: Validate
    }

    public void close() {
        try {
            queue.put(new MessageWrapper(null, true, null));
            closeLatch.await();
        } catch (InterruptedException e) {
            return;
        }
    }

}
