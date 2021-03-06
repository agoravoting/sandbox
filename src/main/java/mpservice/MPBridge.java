package mpservice;

import java.util.LinkedList;
import java.util.List;
import java.util.Arrays;
import mpservice.ModPow;
import java.math.BigInteger;
import java.util.function.Supplier;
import com.squareup.jnagmp.Gmp;

public class MPBridge {

	private static boolean useGmp = false;
	private static boolean useExtractor = false;
	private BigInteger dummy = new BigInteger("2");
	private BigInteger modulus = null;
	private boolean recording = false;
	private boolean replaying = false;
	private boolean replayingDebug = false;
	private LinkedList<ModPow2> requests = new LinkedList<ModPow2>();
	private List<BigInteger> answers = null;
	private List<ModPowResult> answersDebug = null;

	// debug vars
	public static long total = 0;
	public static long found = 0;
	private static long extracted = 0;
	public long before = 0;
	public long beforeZ = 0;
	public long foundZ = 0;
	public boolean debug = false;
	private long beforeTime = 0;

	private static ThreadLocal<MPBridge> instance = new ThreadLocal<MPBridge>() {
		@Override protected MPBridge initialValue() {
			return new MPBridge();
        }
	};

	public static void init(boolean useGmp, boolean useExtractor) {
		MPBridge.useGmp = useGmp;
		MPBridge.useExtractor = useExtractor;
		System.out.println("***************************************************");
		System.out.println("* MPBridge INIT");
		System.out.println("*");
		System.out.println("* useGmp: " + useGmp);
		System.out.println("* useExtractor: " + useExtractor);
		System.out.println("* MPService: " + MPService.toString());
		mpservice.MPService.init();
		System.out.println("***************************************************");
	}

	public static void shutdown() {
		MPService.shutdown();
	}

	public static MPBridge i() {
		return instance.get();
	}

	public static void startRecord() {
		startRecord("2");
	}

	public static void startRecord(String value) {
		i().dummy = new BigInteger(value);
		if(i().requests.size() != 0)	throw new IllegalStateException();
		i().recording = useExtractor;
		i().modulus = null;
	}

	public static ModPow2[] stopRecord() {
		i().recording = false;

		return i().requests.toArray(new ModPow2[0]);
	}

	public static void startReplay(BigInteger[] answers_) {
		if(answers_.length != i().requests.size()) throw new IllegalArgumentException(answers_.length + "!=" + i().requests.size());
		i().answers = new LinkedList<BigInteger>(Arrays.asList(answers_));

		i().replaying = true;
	}

	public static void startReplayDebug(ModPowResult[] answers_) {
		if(answers_.length != i().requests.size()) throw new IllegalArgumentException(answers_.length + "!=" + i().requests.size());
		i().answersDebug = new LinkedList<ModPowResult>(Arrays.asList(answers_));

		i().replayingDebug = true;
	}

	public static void stopReplay() {
		if(i().answers.size() != 0) throw new IllegalStateException();

		i().replaying = false;
	}

	public static void stopReplayDebug() {
		if(i().answersDebug.size() != 0) throw new IllegalStateException();

		i().replayingDebug = false;
	}

	public static void reset() {
		i().requests.clear();
	}

	public static void addModPow(BigInteger base, BigInteger pow, BigInteger mod) {
		MPBridge i = i();
		if(!i.recording) throw new IllegalStateException();
		if(i.modulus == null) {
			i.modulus = mod;
		}
		// FIXME remove this test
		else if(!i.modulus.equals(mod)) {
			throw new RuntimeException(i.modulus + "!=" + mod);
		}
		extracted++;
		i.requests.add(new ModPow2(base, pow));
	}

	public static BigInteger getModPow() {
		if(i().recording) throw new IllegalStateException();

		return i().answers.remove(0);
	}

	public static ModPowResult getModPowDebug() {
		if(i().recording) throw new IllegalStateException();

		return i().answersDebug.remove(0);
	}

	public static LinkedList<ModPow2> getRequests() {
		if(i().recording) throw new IllegalStateException();

		return i().requests;
	}

	public static <T> T ex(Supplier<T> f, String v) {
		a();
	 	startRecord(v);
	 	long now = System.currentTimeMillis();
	 	T ret = f.get();
	 	long r = System.currentTimeMillis() - now;
	 	System.out.println("R: [" + r + " ms]");
	 	mpservice.ModPow2[] reqs = stopRecord();
		b(3);
		if(reqs.length > 0) {
			long now2 = System.currentTimeMillis();
			// FIXME
			// BigInteger[] answers = mpservice.MPService.computeDebug(reqs, i().modulus);
			ModPowResult[] answers = mpservice.MPService.computeDebug(reqs, i().modulus);
			long c = System.currentTimeMillis() - now2;
			// FIXME
			// startReplay(answers);
			startReplayDebug(answers);
			ret = f.get();
			long t = System.currentTimeMillis() - now;
			System.out.println("\nC: [" + c + " ms] T: [" + t + " ms] R+C: [" + (r+c) + " ms]");
			// FIXME
			// stopReplay();
			stopReplayDebug();
		}
		reset();

		return ret;
	}

	public static <T> T ex(Supplier<T> f) {
		return ex(f, "2");
	}

	public static BigInteger modPow(BigInteger base, BigInteger pow, BigInteger mod) {
        MPBridge i = i();
        // FIXME remove this test
        if(i.debug) new Exception().printStackTrace();
        if(i.recording) {
            total++;
            addModPow(base, pow, mod);
            return i.dummy;
        }
        else if(i.replaying) {
            return getModPow();
        }
        else if(i.replayingDebug) {
            ModPowResult result = getModPowDebug();
            boolean ok = base.equals(result.base()) && pow.equals(result.pow()) && mod.equals(result.mod());
            if(!ok) throw new RuntimeException();

            return result.result();
        }
        else {
            total++;
            if(useGmp) {
                return Gmp.modPowInsecure(base, pow, mod);
            }
            else {
                return base.modPow(pow, mod);
            }
        }
    }

    public static BigInteger getModulus() {
    	return i().modulus;
    }

    public static long getExtracted() {
		return extracted;
	}

	public static boolean isRecording() {
		return i().recording;
	}

	public static boolean isReplaying() {
		return i().replaying;
	}

	/****************************** DEBUG STUFF ****************************/

	public static void setDebug(boolean debug) {
		i().debug = debug;
	}

    public static void l() {
		StackTraceElement[] traces = Thread.currentThread().getStackTrace();
		StackTraceElement caller = traces[2];
		System.err.println("* " + caller.getFileName() + ":" + caller.getLineNumber() + "..");
	}

	public static void a() {
		i().before = total;
		i().beforeTime = System.currentTimeMillis();
	}

	public static void b(int trace) {
		MPBridge i = i();
		long diff = total - i.before;
		StackTraceElement[] traces = Thread.currentThread().getStackTrace();
		StackTraceElement caller = traces[trace];
		found += diff;
		long diffTime = System.currentTimeMillis() - i.beforeTime;
		System.err.println(">>> " + caller.getFileName() + ":" + caller.getLineNumber() + " [" + diffTime + " ms] [" + diff + "]" + " (" + found + ", " + total + ") (" + extracted + ")");
	}

	public static void b() {
		b(3);
	}

	public static void y() {
		i().beforeZ = total;
		i().foundZ = found;
	}

	public static void z() {
		long diff = total - i().beforeZ;
		long diffFound = found - i().foundZ;
		StackTraceElement[] traces = Thread.currentThread().getStackTrace();
		StackTraceElement caller = traces[2];
		System.err.println("> " + caller.getFileName() + ":" + caller.getLineNumber() + " [" + diff + "]" + " (" + found + ", " + diffFound + ", " + total + ") (" + extracted + ")");
	}
}