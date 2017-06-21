/*
 * Copyright 2016, gRPC Authors All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.grpc.examples.manualflowcontrol;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.examples.helloworld.GreeterGrpc;
import io.grpc.examples.helloworld.HelloReply;
import io.grpc.examples.helloworld.HelloRequest;
import io.grpc.examples.helloworld.StreamingGreeterGrpc;
import io.grpc.stub.ClientCallStreamObserver;
import io.grpc.stub.ClientResponseObserver;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class ManualFlowControlClient {
    public static void main(String[] args) throws InterruptedException {
        final ExecutorService pool = Executors.newCachedThreadPool();
        final Object done = new Object();

        // Create a channel and a stub
        ManagedChannel channel = ManagedChannelBuilder
                .forAddress("localhost", 50051)
                .usePlaintext(true)
                .build();
        StreamingGreeterGrpc.StreamingGreeterStub stub = StreamingGreeterGrpc.newStub(channel);

        // When using manual flow-control and back-pressure on the client, the ClientResponseObserver handles both
        // request and response streams.
        ClientResponseObserver<HelloRequest, HelloReply> clientResponseObserver =
                new ClientResponseObserver<HelloRequest, HelloReply>() {

            ClientCallStreamObserver<HelloRequest> requestStream;

            @Override
            public void beforeStart(final ClientCallStreamObserver<HelloRequest> requestStream) {
                this.requestStream = requestStream;
                // Set up manual flow control for the response stream. It feels backwards to configure the response
                // stream's flow control using the request stream's observer, but this is the way it is.
                requestStream.disableAutoInboundFlowControl();

                // Set up a back-pressure-aware producer for the request stream. The onReadyHandler will be invoked
                // when the consuming side has enough buffer space to receive more messages.
                //
                // Note: the onReadyHandler is invoked by gRPC's internal thread pool. You can't block in this in
                // method or deadlocks can occur.
                requestStream.setOnReadyHandler(new Runnable() {
                    // An iterator is used so we can pause and resume iteration of the request data.
                    Iterator<String> iterator = names().iterator();

                    @Override
                    public void run() {
                        // Start generating values from where we left off on a non-gRPC thread.
                        pool.execute(new Runnable() {
                            @Override
                            public void run() {
                                // requestStream.isReady() will go false when the consuming side runs out of buffer space and
                                // signals to slow down with back-pressure.
                                while (requestStream.isReady()) {
                                    if (iterator.hasNext()) {
                                        // Simulate doing some work to generate the next value
                                        try { Thread.sleep(100); } catch (InterruptedException e) { e.printStackTrace(); }

                                        // Send more messages if there are more messages to send.
                                        String name = iterator.next();
                                        System.out.println("--> " + name);
                                        HelloRequest request = HelloRequest.newBuilder().setName(name).build();
                                        requestStream.onNext(request);
                                    } else {
                                        // Signal completion if there is nothing left to send.
                                        requestStream.onCompleted();
                                    }
                                }
                            }
                        });
                    }
                });
            }

            @Override
            public void onNext(HelloReply value) {
                System.out.println("<-- " + value.getMessage());
                // Signal the sender to send one message.
                requestStream.request(1);
            }

            @Override
            public void onError(Throwable t) {
                t.printStackTrace();
                synchronized (done) {
                    done.notify();
                }
            }

            @Override
            public void onCompleted() {
                System.out.println("All Done");
                synchronized (done) {
                    done.notify();
                }
            }
        };

        // Note: clientResponseObserver is handling both request and response stream processing.
        stub.sayHelloStreaming(clientResponseObserver);

        synchronized (done) {
            done.wait();
        }

        channel.shutdown();
        pool.shutdown();
        channel.awaitTermination(1, TimeUnit.SECONDS);
    }

    private static List<String> names() {
        List<String> names = new ArrayList<String>();

        names.add("Sophia");
        names.add("Jackson");
        names.add("Emma");
        names.add("Aiden");
        names.add("Olivia");
        names.add("Lucas");
        names.add("Ava");
        names.add("Liam");
        names.add("Mia");
        names.add("Noah");
        names.add("Isabella");
        names.add("Ethan");
        names.add("Riley");
        names.add("Mason");
        names.add("Aria");
        names.add("Caden");
        names.add("Zoe");
        names.add("Oliver");
        names.add("Charlotte");
        names.add("Elijah");
        names.add("Lily");
        names.add("Grayson");
        names.add("Layla");
        names.add("Jacob");
        names.add("Amelia");
        names.add("Michael");
        names.add("Emily");
        names.add("Benjamin");
        names.add("Madelyn");
        names.add("Carter");
        names.add("Aubrey");
        names.add("James");
        names.add("Adalyn");
        names.add("Jayden");
        names.add("Madison");
        names.add("Logan");
        names.add("Chloe");
        names.add("Alexander");
        names.add("Harper");
        names.add("Caleb");
        names.add("Abigail");
        names.add("Ryan");
        names.add("Aaliyah");
        names.add("Luke");
        names.add("Avery");
        names.add("Daniel");
        names.add("Evelyn");
        names.add("Jack");
        names.add("Kaylee");
        names.add("William");
        names.add("Ella");
        names.add("Owen");
        names.add("Ellie");
        names.add("Gabriel");
        names.add("Scarlett");
        names.add("Matthew");
        names.add("Arianna");
        names.add("Connor");
        names.add("Hailey");
        names.add("Jayce");
        names.add("Nora");
        names.add("Isaac");
        names.add("Addison");
        names.add("Sebastian");
        names.add("Brooklyn");
        names.add("Henry");
        names.add("Hannah");
        names.add("Muhammad");
        names.add("Mila");
        names.add("Cameron");
        names.add("Leah");
        names.add("Wyatt");
        names.add("Elizabeth");
        names.add("Dylan");
        names.add("Sarah");
        names.add("Nathan");
        names.add("Eliana");
        names.add("Nicholas");
        names.add("Mackenzie");
        names.add("Julian");
        names.add("Peyton");
        names.add("Eli");
        names.add("Maria");
        names.add("Levi");
        names.add("Grace");
        names.add("Isaiah");
        names.add("Adeline");
        names.add("Landon");
        names.add("Elena");
        names.add("David");
        names.add("Anna");
        names.add("Christian");
        names.add("Victoria");
        names.add("Andrew");
        names.add("Camilla");
        names.add("Brayden");
        names.add("Lillian");
        names.add("John");
        names.add("Natalie");
        names.add("Lincoln");

        return names;
    }
}
