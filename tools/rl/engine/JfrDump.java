import java.io.File;
import java.io.PrintWriter;
import java.util.List;
import org.openjdk.jmc.common.IMCFrame;
import org.openjdk.jmc.common.IMCStackTrace;
import org.openjdk.jmc.common.IMCThread;
import org.openjdk.jmc.common.item.IItem;
import org.openjdk.jmc.common.item.IItemCollection;
import org.openjdk.jmc.common.item.IItemIterable;
import org.openjdk.jmc.common.item.IMemberAccessor;
import org.openjdk.jmc.common.item.ItemFilters;
import org.openjdk.jmc.flightrecorder.JfrAttributes;
import org.openjdk.jmc.flightrecorder.JfrLoaderToolkit;
import org.openjdk.jmc.flightrecorder.jdk.JdkTypeIDs;

/**
 * A Flight Recorder file of any version (Oracle 8's 0.9 too, which the JDK's jfr tool does not read) to text by
 * JMC's parser: a line an execution sample, "thread<TAB>class.method;class.method;..." from the running frame down.
 *
 *   java -cp build/jmc/*;build/engine-host JfrDump in.jfr out.txt
 */
public class JfrDump {
    public static void main(String[] args) throws Exception {
        IItemCollection all = JfrLoaderToolkit.loadEvents(new File(args[0]));
        int n = 0;
        try (PrintWriter out = new PrintWriter(args[1], "UTF-8")) {
            for (IItemIterable it : all.apply(ItemFilters.type(JdkTypeIDs.EXECUTION_SAMPLE))) {
                IMemberAccessor<IMCStackTrace, IItem> st = JfrAttributes.EVENT_STACKTRACE.getAccessor(it.getType());
                IMemberAccessor<IMCThread, IItem> th = JfrAttributes.EVENT_THREAD.getAccessor(it.getType());
                for (IItem item : it) {
                    IMCThread t = th == null ? null : th.getMember(item);
                    StringBuilder b = new StringBuilder(t == null || t.getThreadName() == null ? "?" : t.getThreadName()).append('\t');
                    IMCStackTrace s = st == null ? null : st.getMember(item);
                    if (s != null) {
                        List<? extends IMCFrame> fr = s.getFrames();
                        for (int i = 0; i < fr.size(); i++) {
                            if (i > 0) b.append(';');
                            b.append(fr.get(i).getMethod().getType().getFullName()).append('.').append(fr.get(i).getMethod().getMethodName());
                        }
                    }
                    out.println(b);
                    n++;
                }
            }
        }
        System.err.println(n + " samples");
    }
}
