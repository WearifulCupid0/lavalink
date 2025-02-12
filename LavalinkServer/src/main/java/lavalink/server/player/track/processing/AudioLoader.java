/*
 * Copyright (c) 2021 Freya Arbjerg and contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package lavalink.server.player.track.processing;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

public class AudioLoader implements AudioLoadResultHandler {
    private static final Logger log = LoggerFactory.getLogger(AudioLoader.class);

    private final AudioPlayerManager audioPlayerManager;

    private final CompletableFuture<AudioResult> loadResult = new CompletableFuture<>();
    private final AtomicBoolean used = new AtomicBoolean(false);

    public AudioLoader(AudioPlayerManager audioPlayerManager) {
        this.audioPlayerManager = audioPlayerManager;
    }

    public CompletionStage<AudioResult> load(String identifier) {
        boolean isUsed = this.used.getAndSet(true);
        if (isUsed) {
            throw new IllegalStateException("This loader can only be used once per instance");
        }

        log.trace("Loading item with identifier {}", identifier);
        this.audioPlayerManager.loadItem(identifier, this);

        return loadResult;
    }

    @Override
    public void trackLoaded(AudioTrack audioTrack) {
        log.info("Loaded track " + audioTrack.getInfo().title);
        this.loadResult.complete(new AudioResult(audioTrack));
    }

    @Override
    public void playlistLoaded(AudioPlaylist audioPlaylist) {
        log.info("Loaded playlist " + audioPlaylist.getName());
        this.loadResult.complete(new AudioResult(audioPlaylist));
    }

    @Override
    public void noMatches() {
        log.info("No matches found");
        this.loadResult.complete(new AudioResult());
    }

    @Override
    public void loadFailed(FriendlyException e) {
        log.error("Load failed", e);
        this.loadResult.complete(new AudioResult(e));
    }

}
