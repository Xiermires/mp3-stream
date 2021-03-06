/*******************************************************************************
 * Copyright (c) 2017, Xavier Miret Andres <xavier.mires@gmail.com>
 *
 * Permission to use, copy, modify, and/or distribute this software for any 
 * purpose with or without fee is hereby granted, provided that the above 
 * copyright notice and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES 
 * WITH REGARD TO THIS SOFTWARE INCLUDING ALLIMPLIED WARRANTIES OF 
 * MERCHANTABILITY  AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR 
 * ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES 
 * WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN 
 * ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF 
 * OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
 *******************************************************************************/
package org.mp3stream.vertx;

import io.vertx.core.AbstractVerticle;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.file.AsyncFile;
import io.vertx.core.file.OpenOptions;
import io.vertx.core.http.ServerWebSocket;
import io.vertx.core.streams.Pump;
import io.vertx.ext.web.Router;
import io.vertx.ext.web.handler.StaticHandler;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;

import org.mp3stream.mp3.Mp3FrameIterator;

public class Server extends AbstractVerticle
{
    private static final int HTTP_PORT = 8080;
    private static final int WEBSOCKET_PORT = 8081;

    private static String path;
    private static Streamer player;

    public static void init(String path, int frameCount)
    {
        player = new Streamer(path, frameCount);
        Server.path = path;
        Vertx.vertx().deployVerticle(Server.class.getName());
    }

    @Override
    public void start() throws Exception
    {
        final Router router = Router.router(vertx);

        router.get("/stream").handler(ctx ->
        {
            vertx.fileSystem().readDir(path, ar ->
            {
                final List<String> result = ar.result();
                final List<AsyncFile> files = new ArrayList<>();
                for (String path : result)
                {
                    files.add(vertx.fileSystem().openBlocking(path, new OpenOptions()));
                }
                Pump.pump(new AsyncFileList(files), ctx.response().setChunked(true)).start();
            });
        });

        router.route().handler(StaticHandler.create());

        // The web server.
        vertx.createHttpServer().requestHandler(router::accept).listen(HTTP_PORT);

        // mp3 stream web socket.
        vertx.createHttpServer().websocketHandler(ws ->
        {
            player.connection = ws;
        }).listen(WEBSOCKET_PORT);

        // mp3 streamer thread.
        new Thread(player).start();
    }

    @Override
    public void stop() throws Exception
    {
        player.enabled = false;
        player.connection.close();
        super.stop();
    }

    private static class Streamer implements Runnable
    {
        // the mp3 folder
        private final String streamFolder;
        // Number of frames to be sent together
        private final int frameCount;

        private boolean enabled = true;
        private ServerWebSocket connection = null;

        Streamer(String path, int frameCount)
        {
            this.streamFolder = path;
            this.frameCount = frameCount;
        }

        @Override
        public void run()
        {
            try
            {
                while (enabled)
                {
                    if (connection != null)
                    {
                        for (File file : new File(streamFolder).listFiles())
                        {
                            if (file.isFile())
                            {
                                sentFrames(file);
                            }
                        }
                        // connection.close(); // close connection after all songs have been sent.
                        connection = null;
                    }
                    Thread.sleep(50);
                }
            }
            catch (Exception e)
            {
                System.err.print(e.getMessage());
            }
        }

        private void sentFrames(File song) throws RuntimeException
        {
            // Sent the index of these frames to order at reception
            int idx = 0;

            try (Mp3FrameIterator frames = new Mp3FrameIterator(new FileInputStream(song)))
            {
                while (frames.hasNext())
                {
                    final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    for (int i = 0; frames.hasNext() && i < frameCount; i++)
                    {
                        baos.write(frames.next());
                    }

                    while (connection.writeQueueFull())
                        Thread.sleep(50);

                    final Buffer buffer = Buffer.buffer();
                    buffer.appendInt(idx++); // First 4 bytes : Index of these frames
                    buffer.appendBytes(baos.toByteArray()); // rest : frames data

                    connection.write(buffer);
                }
            }
            catch (Exception e)
            {
                throw new RuntimeException(e);
            }
        }
    }
}
