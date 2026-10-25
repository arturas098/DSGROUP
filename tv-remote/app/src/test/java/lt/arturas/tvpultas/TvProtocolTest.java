package lt.arturas.tvpultas;

import org.junit.Test;
import static org.junit.Assert.*;

/** Known-valid Google TV Polo frames (AndroidTVRemote2 + MiTV-Remote fixtures). */
public class TvProtocolTest {
    @Test public void emptyPairingRequestAckAtEndOfFrameIsValid() throws Exception {
        byte[] ack={8,2,16,(byte)200,1,90,0};
        TvProtocol.Proto.Message parsed=TvProtocol.Proto.parse(ack);
        assertTrue(parsed.has(11));
        assertArrayEquals(new byte[0],parsed.data.get(11));
        assertEquals(200L,parsed.number(2,0));
    }

    @Test public void emptyConfigurationAckAtEndOfFrameIsValid() throws Exception {
        byte[] ack={8,2,16,(byte)200,1,(byte)250,1,0};
        TvProtocol.Proto.Message parsed=TvProtocol.Proto.parse(ack);
        assertTrue(parsed.has(31));
        assertArrayEquals(new byte[0],parsed.data.get(31));
    }

    @Test public void optionsMessageDecodes() throws Exception {
        byte[] ack={8,2,16,(byte)200,1,(byte)162,1,8,18,4,8,3,16,6,24,1};
        TvProtocol.Proto.Message parsed=TvProtocol.Proto.parse(ack);
        assertTrue(parsed.has(20));
        TvProtocol.Proto.Message options=parsed.child(20);
        assertEquals(1L,options.number(3,0));
    }

    @Test public void requestMatchesWorkingAndroidTvProtocol() {
        byte[] request=TvProtocol.Proto.fields(
            TvProtocol.Proto.integer(1,2),TvProtocol.Proto.integer(2,200),
            TvProtocol.Proto.nested(10,TvProtocol.Proto.fields(
                TvProtocol.Proto.text(1,"atvremote"),
                TvProtocol.Proto.text(2,"Mi Remote"))));
        byte[] known={
            8,2,16,(byte)200,1,82,22,10,9,97,116,118,114,101,109,111,116,101,
            18,9,77,105,32,82,101,109,111,116,101
        };
        assertArrayEquals(known,request);
    }
    @Test public void imePacketMatchesRealAndroidTvFixture() {
        // MiTV-Remote upstream fixture generated using AndroidTVRemote2 proto.
        byte[] expected={
            (byte)170,1,22,8,3,16,7,26,16,8,1,18,12,8,5,16,5,26,6,
            104,(byte)195,(byte)169,108,108,111
        };
        assertArrayEquals(expected,TvProtocol.imePacket(3,7,"héllo"));
    }

    @Test public void imeShowCarriesFieldCounter() throws Exception {
        byte[] remote=TvProtocol.Proto.nested(22,
            TvProtocol.Proto.nested(2,TvProtocol.Proto.integer(1,4)));
        assertEquals(4,TvProtocol.readImeFieldCounter(TvProtocol.Proto.parse(remote)));
    }

    @Test public void imeKeyInjectCarriesFieldCounter() throws Exception {
        byte[] remote=TvProtocol.Proto.nested(20,
            TvProtocol.Proto.nested(2,TvProtocol.Proto.integer(1,6)));
        assertEquals(6,TvProtocol.readImeFieldCounter(TvProtocol.Proto.parse(remote)));
    }

    @Test public void imeBatchHasNoShowCounter() throws Exception {
        byte[] remote=TvProtocol.imePacket(7,9,"abc");
        assertEquals(-1,TvProtocol.readImeFieldCounter(TvProtocol.Proto.parse(remote)));
    }

    @Test public void legacyImePacketPlacesCaretAsAndroidTvRemote2() throws Exception {
        byte[] packet=TvProtocol.imePacketVariant(1,1,"Entropy",true);
        TvProtocol.Proto.Message envelope=TvProtocol.Proto.parse(packet);
        TvProtocol.Proto.Message batch=envelope.child(21);
        assertEquals(1L,batch.number(1,-1));
        assertEquals(1L,batch.number(2,-1));
        TvProtocol.Proto.Message edit=batch.child(3);
        TvProtocol.Proto.Message field=edit.child(2);
        assertEquals(6L,field.number(1,-1));
        assertEquals(6L,field.number(2,-1));
    }

    @Test public void currentImePacketPlacesCaretAfterText() throws Exception {
        byte[] packet=TvProtocol.imePacketVariant(1,1,"Entropy",false);
        TvProtocol.Proto.Message envelope=TvProtocol.Proto.parse(packet);
        TvProtocol.Proto.Message batch=envelope.child(21);
        TvProtocol.Proto.Message field=batch.child(3).child(2);
        assertEquals(7L,field.number(1,-1));
        assertEquals(7L,field.number(2,-1));
    }
}
