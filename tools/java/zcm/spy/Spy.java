package zcm.spy;

import javax.swing.*;
import javax.swing.table.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.AffineTransform;

import java.io.*;
import java.util.*;
import zcm.util.*;
import java.lang.reflect.*;

import zcm.zcm.*;

/** Spy main class. **/
public class Spy
{
    ZCM          zcm;
    ZCM.Subscription subs;
    ZCMTypeDatabase handlers;
    long startuTime; // time that zcm-spy started

    HashMap<String,  ChannelData> channelMap = new HashMap<String, ChannelData>();
    ArrayList<ChannelData>        channelList = new ArrayList<ChannelData>();

    ChannelTableModel _channelTableModel = new ChannelTableModel();
    TableSorter  channelTableModel = new TableSorter(_channelTableModel);
    JTable channelTable = new JTable(channelTableModel);
    ChartData chartData;

    ArrayList<SpyPlugin> plugins = new ArrayList<SpyPlugin>();

    JButton clearButton = new JButton("Clear");

    public Spy(String zcmurl, WindowTitleOptions titleOptions) throws IOException
    {
        String title = "ZCM Spy";
        {
            String spacer = "  •  ";
            if (null != titleOptions.label) {
                title += spacer + titleOptions.label;
            }
            if (titleOptions.showURL) {
                title += spacer + zcmurl;
            }
        }
        
        //    sortedChannelTableModel.addMouseListenerToHeaderInTable(channelTable);
        channelTableModel.setTableHeader(channelTable.getTableHeader());
        channelTableModel.setSortingStatus(0, TableSorter.ASCENDING);

        handlers = new ZCMTypeDatabase();

        TableColumnModel tcm = channelTable.getColumnModel();
        tcm.getColumn(0).setMinWidth(140);
        tcm.getColumn(1).setMinWidth(140);
        tcm.getColumn(2).setMaxWidth(100);
        tcm.getColumn(3).setMaxWidth(100);
        tcm.getColumn(4).setMaxWidth(100);
        tcm.getColumn(5).setMaxWidth(100);
        tcm.getColumn(6).setMaxWidth(100);

        JFrame jif = new JFrame(title);
        jif.setLayout(new BorderLayout());
        jif.add(channelTable.getTableHeader(), BorderLayout.PAGE_START);
        // XXX weird bug, if clearButton is added after JScrollPane, we get an error.
        jif.add(clearButton, BorderLayout.SOUTH);
        jif.add(new JScrollPane(channelTable), BorderLayout.CENTER);

        chartData = new ChartData(utime_now());

        // if (isHiDpi()) {
        //     System.out.println("HiDPI detected, setting UI scale to 2.0");
        //     System.setProperty("sun.java2d.uiScale", "2.0");
        // } else {
        //     System.out.println("HiDPI not detected, not setting UI scale");
        // }
         //Float.toString(scaleFactor));
        // SwingDPI.scaleFactor = scaleFeactor;

        jif.setSize(800,600);
        jif.setLocationByPlatform(true);
        jif.setVisible(true);

        
        zcm = new ZCM(zcmurl);
        subs = zcm.subscribe(".*", new MySubscriber());

        zcm.start();

        new HzThread().start();

        clearButton.addActionListener(new ActionListener()
        {
            public void actionPerformed(ActionEvent e)
            {
                channelMap.clear();
                channelList.clear();
                channelTableModel.fireTableDataChanged();
            }
        });

        channelTable.addMouseListener(new MouseAdapter()
        {
            public void mouseClicked(MouseEvent e)
            {
                int mods=e.getModifiersEx();

                if (e.getButton()==3)
                {
                    showPopupMenu(e);
                }
                else if (e.getClickCount() == 2)
                {
                    Point p = e.getPoint();
                    int row = rowAtPoint(p);

                    ChannelData cd = channelList.get(row);
                    boolean got_one = false;
                    for (SpyPlugin plugin : plugins)
                    {
                        if (!got_one && plugin.canHandle(cd.fingerprint)) {

                            // start the plugin
                            (new PluginStarter(plugin, cd)).getAction().actionPerformed(null);

                            got_one = true;
                        }
                    }

                    if (!got_one)
                        createViewer(channelList.get(row));
                }
            }
        });

        jif.addWindowListener(new WindowAdapter()
        {
            public void windowClosing(WindowEvent e)
            {
                System.out.println("Spy quitting");
                System.exit(0);
            }
        });

        ClassDiscoverer.findClasses(new PluginClassVisitor());
        System.out.println("Found "+plugins.size()+" plugins");
        for (SpyPlugin plugin : plugins) {
            System.out.println(" "+plugin);
        }

    }

    class PluginStarter
    {

        private SpyPlugin plugin;
        private ChannelData cd;
        private String name;


        public PluginStarter(SpyPlugin pluginIn, ChannelData cdIn)
        {
            plugin = pluginIn;
            cd = cdIn;
            Action thisAction = plugin.getAction(null, null);
            name = (String) thisAction.getValue("Name");
        }

        public Action getAction() { return new PluginStarterAction(); }

        class PluginStarterAction extends AbstractAction
        {
            public PluginStarterAction() {
                super(name);
            }

            @Override
            public void actionPerformed(ActionEvent e) {

                // for historical reasons, plugins expect a JDesktopPane
                // here we create a JFrame, add a JDesktopPane, and start the
                // plugin by calling its actionPerformed method

                JFrame pluginFrame = new JFrame(cd.name);
                pluginFrame.setLayout(new BorderLayout());
                JDesktopPane pluginJdp = new JDesktopPane();
                pluginFrame.add(pluginJdp);
                pluginFrame.setSize(500, 400);
                pluginFrame.setLocationByPlatform(true);
                pluginFrame.setVisible(true);

                plugin.getAction(pluginJdp, cd).actionPerformed(null);

            }
        }
    }

    class PluginClassVisitor implements ClassDiscoverer.ClassVisitor
    {
        public void classFound(String jar, Class cls)
        {
            Class interfaces[] = cls.getInterfaces();
            for (Class iface : interfaces) {
                if (iface.equals(SpyPlugin.class)) {
                    try {
                        Constructor c = ((Class<?>)cls).getConstructor(new Class[0]);
                        SpyPlugin plugin = (SpyPlugin) c.newInstance(new Object[0]);
                        plugins.add(plugin);
                    } catch (Exception ex) {
                        System.out.println("ex: "+ex);
                    }
                }
            }
        }
    }

    void createViewer(ChannelData cd)
    {

        if (cd.viewerFrame != null && !cd.viewerFrame.isVisible())
        {
            cd.viewerFrame.dispose();
            cd.viewer = null;
        }

        if (cd.viewer == null) {
            cd.viewerFrame = new JFrame(cd.name);

            cd.viewer = new ObjectPanel(cd.name, chartData);

            //    cd.viewer = new ObjectViewer(cd.name, cd.cls, null);
            cd.viewerFrame.setLayout(new BorderLayout());

            // default scroll speed is too slow, so increase it
            JScrollPane viewerScrollPane = new JScrollPane(cd.viewer);
            viewerScrollPane.getVerticalScrollBar().setUnitIncrement(16);
            
            // we need to tell the viewer what its viewport is so that it can
            // make smart decisions about which elements are in view of the user
            // so it can avoid drawing items outside the view
            cd.viewer.setViewport(viewerScrollPane.getViewport());
            
            cd.viewerFrame.add(viewerScrollPane, BorderLayout.CENTER);
            
            cd.viewer.setObject(cd.last, cd.last_utime);

            //jdp.add(cd.viewerFrame);

            cd.viewerFrame.setSize(650,400);
            cd.viewerFrame.setLocationByPlatform(true);
            cd.viewerFrame.setVisible(true);
        } else {
            cd.viewerFrame.setVisible(true);
            //cd.viewerFrame.moveToFront();
        }
    }

    static final long utime_now()
    {
        return System.nanoTime()/1000;
    }

    class ChannelTableModel extends AbstractTableModel
    {
        public int getColumnCount()
        {
            return 8;
        }

        public int getRowCount()
        {
            return channelList.size();
        }

        public Object getValueAt(int row, int col)
        {
            ChannelData cd = channelList.get(row);
            if (cd == null)
                return "";

            switch (col)
            {
                case 0:
                    return cd.name;
                case 1:
                    if (cd.cls == null)
                        return String.format("?? %016x", cd.fingerprint);

                    String s = cd.cls.getName();
                    return s.substring(s.lastIndexOf('.')+1);

                case 2:
                    return ""+cd.nreceived;
                case 3:
                    return String.format("%6.2f", cd.hz);
                case 4:
                    return String.format("%6.2f ms",1000.0/cd.hz); // cd.max_interval/1000.0);
                case 5:
                    return String.format("%6.2f ms",(cd.max_interval - cd.min_interval)/1000.0);
                case 6:
                    return String.format("%6.2f KB/s", (cd.bandwidth/1024.0));
                case 7:
                    return ""+cd.nerrors;
            }
            return "???";
        }

        public String getColumnName(int col)
        {
            switch (col)
            {
                case 0:
                    return "Channel";
                case 1:
                    return "Type";
                case 2:
                    return "Num Msgs";
                case 3:
                    return "Hz";
                case 4:
                    return "1/Hz";
                case 5:
                    return "Jitter";
                case 6:
                    return "Bandwidth";
                case 7:
                    return "Undecodable";
            }
            return "???";
        }

    }

    class MySubscriber implements ZCMSubscriber
    {
        public void messageReceived(ZCM zcm, String channel, ZCMDataInputStream dins)
        {
            Object o = null;
            ChannelData cd = channelMap.get(channel);
            int msg_size = 0;

            try {
                msg_size = dins.available();
                long fingerprint = (msg_size >=8) ? dins.readLong() : -1;
                dins.reset();

                Class cls = handlers.getClassByFingerprint(fingerprint);

                if (cd == null) {
                    cd = new ChannelData();
                    cd.name = channel;
                    cd.cls = cls;
                    cd.fingerprint = fingerprint;
                    cd.row = channelList.size();

                    synchronized(channelList) {
                        channelMap.put(channel, cd);
                        channelList.add(cd);
                        _channelTableModel.fireTableDataChanged();
                    }

                } else {
                    if (cls != null && cd.cls != null && !cd.cls.equals(cls)) {
                        System.out.println("WARNING: Class changed for channel "+channel);
                        cd.nerrors++;
                    }
                }

                long utime = utime_now();
                long interval = utime - cd.last_utime;
                cd.hz_min_interval = Math.min(cd.hz_min_interval, interval);
                cd.hz_max_interval = Math.max(cd.hz_max_interval, interval);
                cd.hz_bytes += msg_size;
                cd.last_utime = utime;

                cd.nreceived++;

                o = ((Class<?>)cd.cls).getConstructor(ZCMDataInputStream.class).newInstance(dins);
                cd.last = o;

                if (cd.viewer != null)
                    cd.viewer.setObject(o, cd.last_utime);

            } catch (NullPointerException ex) {
                cd.nerrors++;
            } catch (IOException ex) {
                cd.nerrors++;
                System.out.println("Spy.messageReceived ex: "+ex);
            } catch (NoSuchMethodException ex) {
                cd.nerrors++;
                System.out.println("Spy.messageReceived ex: "+ex);
            } catch (InstantiationException ex) {
                cd.nerrors++;
                System.out.println("Spy.messageReceived ex: "+ex);
            } catch (IllegalAccessException ex) {
                cd.nerrors++;
                System.out.println("Spy.messageReceived ex: "+ex);
            } catch (InvocationTargetException ex) {
                cd.nerrors++;
                // these are almost always spurious
                //System.out.println("ex: "+ex+"..."+ex.getTargetException());
            }
        }
    }

    class HzThread extends Thread
    {
        public HzThread()
        {
            setDaemon(true);
        }

        public void run()
        {
            while (true)
            {
                long utime = utime_now();

                synchronized(channelList)
                {
                    for (ChannelData cd : channelList)
                    {
                        long diff_recv = cd.nreceived - cd.hz_last_nreceived;
                        cd.hz_last_nreceived = cd.nreceived;
                        long dutime = utime - cd.hz_last_utime;
                        cd.hz_last_utime = utime;

                        cd.hz = diff_recv / (dutime/1000000.0);

                        cd.min_interval = cd.hz_min_interval;
                        cd.max_interval = cd.hz_max_interval;
                        cd.hz_min_interval = 9999;
                        cd.hz_max_interval = 0;
                        cd.bandwidth = cd.hz_bytes / (dutime/1000000.0);
                        cd.hz_bytes = 0;
                    }
                }

                int selrow = channelTable.getSelectedRow();
                channelTableModel.fireTableDataChanged();
                if (selrow >= 0)
                    channelTable.setRowSelectionInterval(selrow, selrow);

                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ex) {
                }
            }
        }

    }

    class DefaultViewer extends AbstractAction
    {
        ChannelData cd;

        public DefaultViewer(ChannelData cd)
        {
            super("Structure Viewer...");
            this.cd = cd;
        }

        public void actionPerformed(ActionEvent e)
        {
            createViewer(cd);
        }
    }

    int rowAtPoint(Point p)
    {
        int physicalRow = channelTable.rowAtPoint(p);

        return channelTableModel.modelIndex(physicalRow);
    }

    public void showPopupMenu(MouseEvent e)
    {
        Point p = e.getPoint();
        int row = rowAtPoint(p);
        ChannelData cd = channelList.get(row);
        JPopupMenu jm = new JPopupMenu("Viewers");

        int prow = channelTable.rowAtPoint(p);
        channelTable.setRowSelectionInterval(prow, prow);

        jm.add(new DefaultViewer(cd));



        if (cd.cls != null)
        {
            for (SpyPlugin plugin : plugins)
            {
                if (plugin.canHandle(cd.fingerprint))
                {
                    jm.add(new PluginStarter(plugin, cd).getAction());

                    //jm.add(plugin.getAction(this_desktop_pane, cd));
                }
            }
        }

        jm.show(channelTable, e.getX(), e.getY());
    }

    public static void usage()
    {
        System.err.println("usage: zcm-spy [options]");
        System.err.println("");
        System.err.println("zcm-spy is the Lightweight Communications and Marshalling traffic ");
        System.err.println("inspection utility.  It is a graphical tool for viewing messages received on ");
        System.err.println("an ZCM network, and is analagous to tools like Ethereal/Wireshark and tcpdump");
        System.err.println("in that it is able to inspect all ZCM messages received and provide information");
        System.err.println("and statistics on the channels used.");
        System.err.println("");
        System.err.println("When given appropriate ZCM type definitions, zcm-spy is able to");
        System.err.println("automatically detect and decode messages, and can display the individual fields");
        System.err.println("of recognized messages.  zcm-spy is limited to displaying statistics for");
        System.err.println("unrecognized messages.");
        System.err.println("");
        System.err.println("Options:");
        System.err.println("  -h, --help             Shows this help text and exits");
        System.err.println("  -l, --zcm-url=URL      Use the specified ZCM URL");
        System.err.println("  -t, --title [LABEL]    Display LABEL in the window title");
        System.err.println("  --title-url            Display the ZCM URL in the title");
        System.err.println("");
        System.exit(1);
    }

    static class WindowTitleOptions {
        String label = null;
        boolean showURL = false;
    }

    private static int getScreenDPI() {
        // A bit of a hack to detect screen DPI.  If you do this through Java, it's already too late to set the uiScale property.
        try {
            // Execute the command through a shell
            String[] cmd = {"/bin/sh", "-c", "xdpyinfo | grep resolution | awk '{print $2}'"};
            Process process = Runtime.getRuntime().exec(cmd);
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String line;
            while ((line = reader.readLine()) != null) {
                String[] dpiValues = line.split("x");
                int dpiX = Integer.parseInt(dpiValues[0]);
                int dpiY = Integer.parseInt(dpiValues[1]);
                return (dpiX + dpiY) / 2; // Average the DPI values
            }
            // Print any errors from the command execution
            while ((line = errorReader.readLine()) != null) {
                System.err.println("Error: " + line);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        // Default DPI if unable to fetch
        return 96;
    }
    
    public static void main(String args[])
    {
        int screenDpi = getScreenDPI();
        System.out.println("Screen DPI: " + screenDpi);
        if (screenDpi == 144) {
            System.setProperty("sun.java2d.uiScale", "2.0");
        }
        // System.out.println(Double.toString(Math.round(getScreenDPI() / 96.0)));
        // System.out.println(Double.toString(Math.round(148.0 / 96.0)));
        // System.setProperty("sun.java2d.uiScale", "1.5");//Double.toString(Math.round(getScreenDPI() / 96.0)));

        // check if the JRE is supplied by gcj, and warn the user if it is.
        if(System.getProperty("java.vendor").indexOf("Free Software Foundation") >= 0) {
            System.err.println("WARNING: Detected gcj. zcm-spy is not known to work well with gcj.");
            System.err.println("         The Sun JRE is recommended.");
        }

        final String ARG_ZCM_URL = "--zcm-url";
        final String ARG_ZCM_URL_EQ = ARG_ZCM_URL + "=";
        final String ARG_TITLE = "--title";
        final String ARG_TITLE_URL = "--title-url";

        String zcmurl = null;
        WindowTitleOptions titleOptions = new WindowTitleOptions();
        for (int optind = 0; optind < args.length; optind++) {
            String c = args[optind];
            boolean hasParam = optind + 1 < args.length;

            if (c.equals("-h") || c.equals("--help")) {
                usage();
            } else if (c.equals("-l") || c.equals(ARG_ZCM_URL) || c.startsWith(ARG_ZCM_URL_EQ)) {
                String optarg = null;

                if (c.startsWith(ARG_ZCM_URL_EQ)) {
                    optarg = c.substring(ARG_ZCM_URL_EQ.length());
                } else if (hasParam) {
                    optind++;
                    optarg = args[optind];
                }

                if (null == optarg || optarg.isEmpty()) {
                    usage();
                } else {
                    zcmurl = optarg;
                }
            } else if (c.equals("-t") || c.equals(ARG_TITLE)) {
                if (hasParam) {
                    optind++;
                    titleOptions.label = args[optind];
                } else {
                    usage();

                }

            } else if (c.equals(ARG_TITLE_URL)) {
                titleOptions.showURL = true;

            } else {
                usage();
            }
        }

        try {
            new Spy(zcmurl, titleOptions);
        } catch (IOException ex) {
            System.out.println(ex);
        }
    }
}
