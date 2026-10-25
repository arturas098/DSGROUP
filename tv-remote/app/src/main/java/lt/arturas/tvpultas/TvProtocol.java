package lt.arturas.tvpultas;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.*;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.util.*;
import java.util.concurrent.*;
import javax.net.ssl.*;
import javax.security.auth.x500.X500Principal;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.asn1.x509.BasicConstraints;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import java.security.cert.Certificate;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Independent Android TV Remote v2 client. All commands travel over the local
 * network using TLS. The TV's 6-hex-digit PIN authenticates initial pairing.
 * A pinned TV certificate and an app-private PKCS#12 identity protect reconnection.
 */
final class TvProtocol {
    interface Listener {
        void onStatus(String message, boolean connected);
        void onPairingCode();
        void onPaired();
        void onTvKeyboard();
    }

    private static final String KEY_ALIAS = "tv-pultas";
    private static final char[] STORE_PASSWORD = "tvpultas-v2".toCharArray();
    private final AtomicInteger attempt = new AtomicInteger();
    private final Context context;
    private final SharedPreferences preferences;
    private final Listener listener;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService writes = Executors.newSingleThreadExecutor();
    private volatile SSLSocket remote;
    private volatile OutputStream remoteOut;
    private volatile boolean running = true;
    private volatile boolean ready;
    private volatile int imeCounter, fieldCounter;
    private volatile int features;
    private SSLSocket pairing;
    private String address;
    private volatile String pairingStage = "nepradėta";
    private volatile String tlsVersion = "TLS1.2";
    private volatile String networkDetails = "";
    private X509Certificate pairingPeer;

    TvProtocol(Context ctx, Listener callback) {
        context = ctx.getApplicationContext();
        preferences = context.getSharedPreferences("tv_remote_settings", Context.MODE_PRIVATE);
        listener = callback;
    }

    String lastHost() { return preferences.getString("host", ""); }
    boolean hasPairing() { return !preferences.getString("pin", "").isEmpty(); }
    boolean isConnected() { return ready; }

    void connectPrevious() {
        String host = lastHost();
        if (!host.isEmpty() && hasPairing()) connect(host);
    }

    void connect(String host) {
        address = host.trim();
        int ticket = attempt.incrementAndGet();
        closeRemote();
        network.execute(() -> {
            while (running && ticket == attempt.get()) {
                try {
                    publish("Jungiamasi prie " + host + "…", false);
                    connectRemote(host.trim());
                } catch (Exception error) {
                    if (running && ticket == attempt.get()) publish("Ryšio nėra: " + summarize(error), false);
                } finally {
                    closeRemote();
                }
                if (running && ticket == attempt.get())
                    try { Thread.sleep(2500); } catch (InterruptedException ignored) { return; }
            }
        });
    }

    void beginPairing(String host) {
        address = host.trim();
        int ticket = attempt.incrementAndGet();
        closeRemote();
        network.execute(() -> {
            // Most Android TV Remote Services expect "atvremote".
            // Some Android TV Remote npm integrations use "androidtv-remote".
            // When the TV hangs up on request #1, try the second service name
            // on a fresh TLS socket, without requiring another phone action.
            StringBuilder errors = new StringBuilder();
            String[] services = {"atvremote", "androidtv-remote"};
            for (int profile = 0; profile < services.length; profile++) {
                if (!running || ticket != attempt.get()) return;
                String service = services[profile];
                try {
                    closePairing();
                    pairingStage = "TCP jungtis į 6467";
                    tlsVersion = "TLSv1.2";
                    publish("Poravimas: profilis " + (profile + 1) + "/2…", false);
                    pairing = tls(address, 6467, false);
                    pairingPeer = (X509Certificate) pairing.getSession().getPeerCertificates()[0];
                    java.security.cert.Certificate[] local = pairing.getSession().getLocalCertificates();
                    if (local == null || local.length == 0)
                        throw new IOException("TLS klientas nepateikė sertifikato");
                    pairing.setSoTimeout(12000);
                    OutputStream out = pairing.getOutputStream();
                    pairingStage = "užklausa TV (1/3), profilis=" + service;
                    byte[] request = envelope(10, Proto.fields(
                        Proto.text(1, service), Proto.text(2, "TV Pultas")));
                    sendFrame(out, request);
                    InputStream in = pairing.getInputStream();
                    for (int n = 0; n < 8; n++) {
                        Proto.Message m = Proto.parse(readFrame(in));
                        verifyStatus(m);
                        if (m.has(11)) {
                            pairingStage = "TV patvirtino, parinktys (2/3)";
                            publish("Poravimas 2/3: TV atsakė…", false);
                            sendFrame(out, envelope(20, Proto.fields(
                                Proto.nested(1, Proto.fields(
                                    Proto.integer(1, 3), Proto.integer(2, 6))),
                                Proto.integer(3, 1))));
                        } else if (m.has(20)) {
                            pairingStage = "TV parinktys gautos (3/3)";
                            publish("Poravimas 3/3: kodas bus TV ekrane…", false);
                            sendFrame(out, envelope(30, Proto.fields(
                                Proto.nested(1, Proto.fields(
                                    Proto.integer(1, 3), Proto.integer(2, 6))),
                                Proto.integer(2, 1))));
                        } else if (m.has(31)) {
                            pairingStage = "TV rodo kodą";
                            pairing.setSoTimeout(60000);
                            publish("TV rodo 6 simbolių kodą", false);
                            listener.onPairingCode();
                            return;
                        } else {
                            throw new IOException("Netikėtas TV pranešimas");
                        }
                    }
                    throw new IOException("Per daug poravimo pranešimų");
                } catch (Exception e) {
                    String detail = "profilis " + (profile+1) + " (" + service + "): "
                        + summarize(e) + " [" + pairingStage + "]";
                    if (errors.length() > 0) errors.append(" | ");
                    errors.append(detail);
                    closePairing();
                    boolean retryAlternative = profile == 0 &&
                        pairingStage.startsWith("užklausa TV (1/3)") &&
                        (e instanceof EOFException ||
                         e instanceof java.net.SocketTimeoutException ||
                         e instanceof java.net.SocketException);
                    if (!retryAlternative) break;
                    publish("TV atmetė pirmą poravimo profilį, bandome kitą…", false);
                }
            }
            if (running && ticket == attempt.get()) {
                publish("Poravimo klaida: " + errors
                    + " | TCP6467=" + portProbe(address,6467)
                    + "; TCP6466=" + portProbe(address,6466), false);
            }
        });
    }

    void submitPairingCode(String code) {
        network.execute(() -> {
            try {
                if (pairing == null || pairingPeer == null) throw new IOException("Poravimas nepradėtas");
                byte[] secret = pairingDigest(code);
                pairingStage = "siunčiamas kodas";
                sendFrame(pairing.getOutputStream(), envelope(40, Proto.fields(Proto.data(1, secret))));
                Proto.Message reply = Proto.parse(readFrame(pairing.getInputStream()));
                verifyStatus(reply);
                if (!reply.has(41)) throw new IOException("TV atmetė kodą");
                String fingerprint = hex(MessageDigest.getInstance("SHA-256").digest(pairingPeer.getEncoded()));
                preferences.edit().putString("host", address).putString("pin", fingerprint).apply();
                closePairing();
                listener.onPaired();
                publish("Suporuota", false);
                connect(address);
            } catch (Exception e) {
                publish("Kodas netinka: " + summarize(e), false);
                closePairing();
            } finally {
                closeRemote();
            }
        });
    }

    private byte[] pairingDigest(String text) throws Exception {
        String code = text.trim().toUpperCase(Locale.ROOT);
        if (!code.matches("[0-9A-F]{6}")) throw new IOException("Reikia 6 šešioliktainių simbolių");
        RSAPublicKey mine = (RSAPublicKey) identityCertificate().getPublicKey();
        RSAPublicKey theirs = (RSAPublicKey) pairingPeer.getPublicKey();
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        sha.update(unsigned(mine.getModulus()));
        sha.update(unsigned(mine.getPublicExponent()));
        sha.update(unsigned(theirs.getModulus()));
        sha.update(unsigned(theirs.getPublicExponent()));
        sha.update(new byte[]{
            (byte) Integer.parseInt(code.substring(2,4),16),
            (byte) Integer.parseInt(code.substring(4,6),16)
        });
        byte[] digest = sha.digest();
        if ((digest[0] & 0xff) != Integer.parseInt(code.substring(0,2),16))
            throw new IOException("Klaidingas TV kodas");
        return digest;
    }

    private static byte[] unsigned(BigInteger n) {
        byte[] data = n.toByteArray();
        return data.length > 1 && data[0] == 0 ? Arrays.copyOfRange(data, 1, data.length) : data;
    }

    private void connectRemote(String host) throws Exception {
        SSLSocket socket = tls(host, 6466, true);
        socket.setSoTimeout(9000);
        remote = socket;
        remoteOut = socket.getOutputStream();
        InputStream input = socket.getInputStream();
        ready = false;
        while (running && !socket.isClosed()) {
            Proto.Message m = Proto.parse(readFrame(input));
            if (m.has(1)) {
                int offered = (int)m.child(1).number(1,0);
                features = offered & (1|2|4|32|64|512);
                sendRemote(Proto.nested(1, Proto.fields(
                    Proto.integer(1, features),
                    Proto.nested(2, Proto.fields(
                        Proto.text(1,"TV Pultas"),Proto.text(2,"Arturas"),
                        Proto.integer(3,1),Proto.text(4,"1"),
                        Proto.text(5,"lt.arturas.tvpultas"),Proto.text(6,"1.0"))))));
            } else if (m.has(2)) {
                sendRemote(Proto.nested(2, Proto.fields(Proto.integer(1,features))));
            } else if (m.has(8)) {
                sendRemote(Proto.nested(9, Proto.fields(Proto.integer(1, m.child(8).number(1,0)))));
            } else if (m.has(40)) {
                ready = true;
                socket.setSoTimeout(0);
                publish("Prisijungta", true);
            } else if (m.has(21)) {
                Proto.Message edit = m.child(21);
                imeCounter = (int)edit.number(1,0);
                fieldCounter = (int)edit.number(2,0);
                if (ready) listener.onTvKeyboard();
            } else if (m.has(22)) {
                if (ready) listener.onTvKeyboard();
            }
        }
    }

    void key(int keyCode) {
        if (!ready) return;
        sendRemote(Proto.nested(10, Proto.fields(Proto.integer(1,keyCode),Proto.integer(2,3))));
    }

    void text(String content) {
        if (!ready || content == null || content.isEmpty()) return;
        int caret = Math.max(0,content.length()-1);
        byte[] object = Proto.fields(Proto.integer(1,caret),Proto.integer(2,caret),Proto.text(3,content));
        byte[] edit = Proto.fields(Proto.integer(1,1),Proto.nested(2,object));
        sendRemote(Proto.nested(21, Proto.fields(
            Proto.integer(1,imeCounter),Proto.integer(2,fieldCounter),Proto.nested(3,edit))));
    }

    void shutdown() {
        running = false;
        closePairing();
        closeRemote();
        network.shutdownNow();
        writes.shutdownNow();
    }

    private void sendRemote(byte[] message) {
        final OutputStream stream = remoteOut;
        if (stream == null) return;
        writes.execute(() -> {
            try { synchronized(stream) { sendFrame(stream, message); } }
            catch (IOException e) { closeRemote(); publish("Ryšys nutrūko", false); }
        });
    }

    private void publish(String message, boolean connected) {
        listener.onStatus(message, connected);
    }

    private static String portProbe(String host, int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host,port),2500);
            return "ATIDARYTAS";
        } catch (Exception e) {
            return "NEPASIEKIAMAS(" + e.getClass().getSimpleName() + ")";
        }
    }

    private SSLSocket tls(String host, int port, boolean enforcePin) throws Exception {
        KeyManagerFactory km = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        km.init(identityStore(), STORE_PASSWORD);
        final String pinned = preferences.getString("pin","");
        X509TrustManager trust = new X509TrustManager() {
            @Override public void checkClientTrusted(X509Certificate[] certs, String auth) {}
            @Override public void checkServerTrusted(X509Certificate[] certs, String auth)
                    throws java.security.cert.CertificateException {
                if (certs == null || certs.length == 0)
                    throw new java.security.cert.CertificateException("TV certificate missing");
                if (enforcePin) {
                    try {
                        String actual = hex(MessageDigest.getInstance("SHA-256")
                                .digest(certs[0].getEncoded()));
                        if (pinned.isEmpty() || !pinned.equals(actual))
                            throw new java.security.cert.CertificateException(
                                    "TV sertifikatas pasikeitė – poruokite iš naujo");
                    } catch (NoSuchAlgorithmException ex) {
                        throw new java.security.cert.CertificateException(ex);
                    }
                }
            }
            @Override public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        };
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(km.getKeyManagers(), new TrustManager[]{trust}, new SecureRandom());

        // Independent, timed TCP connection followed by TLS wrapping, as used
        // by EndLess728/MiTV-Remote's Android transport. Some Android TV Remote
        // servers hang rather than reject the TLS 1.3 ClientHello.
        pairingStage = "TCP jungtis į " + port;
        Socket plain = new Socket();
        plain.setTcpNoDelay(true);
        plain.setKeepAlive(true);
        try {
            plain.connect(new InetSocketAddress(host,port),6000);
            pairingStage = "TCP OK, TLS 1.2 handshake (portas " + port + ")";
            tlsVersion = "TLSv1.2";
            SSLSocket socket = (SSLSocket)ssl.getSocketFactory()
                    .createSocket(plain,host,port,true);
            socket.setEnabledProtocols(new String[]{"TLSv1.2"});
            socket.setSoTimeout(18000);
            try {
                socket.startHandshake();
                tlsVersion = socket.getSession().getProtocol();
                socket.setSoTimeout(7000);
                pairingStage = "TLS sėkmingas, laukiama TV atsakymo";
                return socket;
            } catch (Exception fail) {
                socket.close();
                throw new IOException("TLS 1.2 handshake: " + summarize(fail),fail);
            }
        } catch (Exception fail) {
            try { plain.close(); } catch (Exception ignored) {}
            throw fail;
        }
    }

    private synchronized X509Certificate identityCertificate() throws Exception {
        return (X509Certificate) identityStore().getCertificate(KEY_ALIAS);
    }

    private synchronized KeyStore identityStore() throws Exception {
        File certFile = new File(context.getFilesDir(), "tv-pultas-v4-identity.p12");
        KeyStore store = KeyStore.getInstance("PKCS12");
        if (certFile.exists()) {
            try (FileInputStream in = new FileInputStream(certFile)) {
                store.load(in, STORE_PASSWORD);
                if (store.containsAlias(KEY_ALIAS)) return store;
            } catch (Exception broken) {
                // Invalid/old store: generate a new identity. TV needs pairing again.
                certFile.delete();
            }
        }
        KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA");
        rsa.initialize(2048);
        KeyPair identity = rsa.generateKeyPair();
        long now = System.currentTimeMillis();
        X500Principal name = new X500Principal("CN=atvremote");
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
            name, BigInteger.valueOf(now), new Date(now - 86400000L),
            new Date(now + 10L * 365 * 86400000L), name, identity.getPublic());
        // Match the certificate profile used by the widely deployed
        // Home Assistant Android TV Remote integration (tronikos/androidtvremote2):
        // self-signed RSA certificate, CA basic constraint, and DNS SAN.
        certBuilder.addExtension(Extension.basicConstraints, false,
                new BasicConstraints(0));
        certBuilder.addExtension(Extension.subjectAlternativeName, false,
                new GeneralNames(new GeneralName(GeneralName.dNSName, "atvremote")));
        X509Certificate certificate = new JcaX509CertificateConverter().getCertificate(
            certBuilder.build(new JcaContentSignerBuilder("SHA256withRSA").build(identity.getPrivate())));
        certificate.verify(identity.getPublic());
        store.load(null,null);
        store.setKeyEntry(KEY_ALIAS, identity.getPrivate(), STORE_PASSWORD, new Certificate[]{certificate});
        try (FileOutputStream out = new FileOutputStream(certFile)) {
            store.store(out, STORE_PASSWORD);
        }
        return store;
    }

    private void verifyStatus(Proto.Message m) throws IOException {
        long status = m.number(2,200);
        if (status != 200) throw new IOException("TV atsakymas " + status);
    }

    private static byte[] envelope(int field, byte[] inner) {
        return Proto.fields(Proto.integer(1,2),Proto.integer(2,200),Proto.nested(field,inner));
    }

    private static String summarize(Exception e) {
        String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        if (detail.contains("TLSV1_ALERT_CERTIFICATE_REQUIRED") || detail.contains("SSLV3_ALERT_BAD_CERTIFICATE"))
            return "TV atmetė sertifikatą – poruokite iš naujo. " + detail;
        return detail;
    }
    private static String hex(byte[] bytes) {
        StringBuilder b=new StringBuilder();
        for(byte x:bytes) b.append(String.format(Locale.ROOT,"%02x",x&0xff));
        return b.toString();
    }
    private void closeRemote() {
        ready=false;
        SSLSocket s=remote;
        remote=null; remoteOut=null;
        if(s!=null) try{s.close();}catch(Exception ignored){}
    }
    private void closePairing() {
        SSLSocket s=pairing; pairing=null; pairingPeer=null;
        if(s!=null) try{s.close();}catch(Exception ignored){}
    }

    /** Minimal protocol buffers framing; no generated code/runtime required. */
    static final class Proto {
        static class Message {
            final Map<Integer,byte[]> data=new HashMap<>();
            final Map<Integer,Long> scalars=new HashMap<>();
            boolean has(int id) { return data.containsKey(id) || scalars.containsKey(id); }
            long number(int id,long fallback) { return scalars.getOrDefault(id,fallback); }
            Message child(int id) throws IOException { return parse(data.get(id)); }
        }
        static byte[] fields(byte[]... values) {
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            for(byte[] item: values) try { b.write(item); } catch(IOException impossible) {}
            return b.toByteArray();
        }
        static byte[] integer(int field,long value) {
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            varint(b,(field<<3));
            varint(b,value);
            return b.toByteArray();
        }
        static byte[] text(int field,String value) { return data(field,value.getBytes(StandardCharsets.UTF_8)); }
        static byte[] nested(int field,byte[] payload) { return data(field,payload); }
        static byte[] data(int field,byte[] payload) {
            ByteArrayOutputStream b=new ByteArrayOutputStream();
            varint(b,(field<<3)|2);
            varint(b,payload.length);
            b.write(payload,0,payload.length);
            return b.toByteArray();
        }
        static void varint(OutputStream stream,long value) {
            try {
                while((value & ~127L)!=0L) {
                    stream.write(((int)value & 127)|128);
                    value >>>= 7;
                }
                stream.write((int)value);
            } catch (IOException e) { throw new RuntimeException(e); }
        }
        static Message parse(byte[] packet) throws IOException {
            if(packet==null) return new Message();
            ByteArrayInputStream stream=new ByteArrayInputStream(packet);
            Message msg=new Message();
            while(stream.available()>0) {
                long tag=readVarint(stream);
                int id=(int)(tag>>>3), wire=(int)(tag&7);
                if(id<1) throw new IOException("Bad proto field");
                if(wire==0) msg.scalars.put(id,readVarint(stream));
                else if(wire==2) {
                    long len=readVarint(stream);
                    if(len<0||len>stream.available()) throw new IOException("Bad proto size");
                    byte[] buffer=new byte[(int)len];
                    // A zero-length nested protobuf message is VALID, especially
                    // pairingRequestAck {} and configurationAck {}. Java's
                    // ByteArrayInputStream.read(new byte[0]) returns -1 at EOF,
                    // not 0, so never call read when len == 0.
                    int offset=0;
                    while(offset<buffer.length) {
                        int n=stream.read(buffer,offset,buffer.length-offset);
                        if(n<=0) throw new EOFException("Truncated protobuf field " + id);
                        offset+=n;
                    }
                    msg.data.put(id,buffer);
                } else if(wire==1) {
                    if(stream.skip(8)!=8) throw new EOFException();
                } else if(wire==5) {
                    if(stream.skip(4)!=4) throw new EOFException();
                } else throw new IOException("Unsupported protobuf wire type "+wire);
            }
            return msg;
        }
    }
    private static long readVarint(InputStream input) throws IOException {
        long n=0;
        for(int shift=0;shift<64;shift+=7) {
            int b=input.read();
            if(b<0) throw new EOFException();
            n |= (long)(b&127)<<shift;
            if((b&128)==0) return n;
        }
        throw new IOException("Varint too long");
    }
    private static byte[] readFrame(InputStream input) throws IOException {
        long count=readVarint(input);
        if(count>2_000_000 || count<0) throw new IOException("TV pranešimas per didelis");
        byte[] bytes=new byte[(int)count];
        int soFar=0;
        while(soFar<bytes.length) {
            int n=input.read(bytes,soFar,bytes.length-soFar);
            if(n<0) throw new EOFException();
            soFar+=n;
        }
        return bytes;
    }
    private static void sendFrame(OutputStream out,byte[] bytes) throws IOException {
        Proto.varint(out,bytes.length);
        out.write(bytes);
        out.flush();
    }
}
