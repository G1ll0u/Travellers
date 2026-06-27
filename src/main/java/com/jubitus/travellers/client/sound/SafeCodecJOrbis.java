package com.jubitus.travellers.client.sound;

import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import paulscode.sound.SoundBuffer;
import paulscode.sound.codecs.CodecJOrbis;

import java.net.URL;

/**
 * Drop-in replacement for CodecJOrbis registered via SoundSetupEvent.
 *
 * When readHeader() fails, JOrbis leaves internal state corrupt (vd==null).
 * If that exception propagates up through PaulsCode's CommandThread while it
 * holds its internal queue lock, it can deadlock with the StreamThread — freezing
 * the client. All three methods are wrapped so failures are always dropped cleanly.
 */
@SideOnly(Side.CLIENT)
public class SafeCodecJOrbis extends CodecJOrbis {

    private static final org.apache.logging.log4j.Logger LOG =
            org.apache.logging.log4j.LogManager.getLogger("TravellerSound");

    @Override
    public boolean initialize(URL url) {
        try {
            return super.initialize(TravellerSoundCache.redirect(url));
        } catch (Throwable t) {
            LOG.warn("[SafeCodec] initialize failed for {}: {}", url, t.toString());
            return false;
        }
    }

    @Override
    public SoundBuffer read() {
        try {
            return super.read();
        } catch (Throwable t) {
            LOG.warn("[SafeCodec] read() failed: {}", t.toString());
            return null;
        }
    }

    @Override
    public SoundBuffer readAll() {
        try {
            return super.readAll();
        } catch (Throwable t) {
            LOG.warn("[SafeCodec] readAll() failed: {}", t.toString());
            return null;
        }
    }
}
