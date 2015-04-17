package org.thaliproject.p2p.wifitestpower2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.CountDownTimer;
import android.os.Handler;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;


/**
 * Created by juksilve on 28.2.2015.
 */

public class WifiServiceSearcher {

    private Context context;
    private BroadcastReceiver receiver;
    private IntentFilter filter;

    private final WifiBase.WifiStatusCallBack callback;
    private WifiP2pManager p2p;
    private WifiP2pManager.Channel channel;
    private WifiP2pManager.DnsSdServiceResponseListener serviceListener;

    enum ServiceState{
        NONE,
        DiscoverPeer,
        DiscoverService
    }
    ServiceState myServiceState = ServiceState.NONE;
    List<ServiceItem> myServiceList = new ArrayList<ServiceItem>();

    CountDownTimer ServiceDiscoveryTimeOutTimer = new CountDownTimer(60000, 100000) {
        public void onTick(long millisUntilFinished) {
            // not using
        }
        public void onFinish() {
            stopDiscovery();
            startServiceDiscovery();
        }
    };

    CountDownTimer peerDiscoveryTimer = null;

    public WifiServiceSearcher(Context Context, WifiP2pManager Manager, WifiP2pManager.Channel Channel, WifiBase.WifiStatusCallBack handler) {
        this.context = Context;
        this.p2p = Manager;
        this.channel = Channel;
        this.callback = handler;

        Random ran = new Random(System.currentTimeMillis());

        // if this 4 seconds minimum, then we see this
        // triggering before we got all services
        long millisInFuture = 5000 + (ran.nextInt(5000));

        debug_print("peerDiscoveryTimer timeout value:" + millisInFuture);

        peerDiscoveryTimer = new CountDownTimer(millisInFuture, 1000) {
            public void onTick(long millisUntilFinished) {
                // not using
            }
            public void onFinish() {
                myServiceState = ServiceState.NONE;
                if(callback != null) {
                    callback.gotServicesList(myServiceList);
                }

                // lets have a break, and re-start discovery after it
                ServiceDiscoveryTimeOutTimer.cancel();
                ServiceDiscoveryTimeOutTimer.start();
            }
        };
    }

    public List<ServiceItem> GetServiceList(){
        return  myServiceList;
    }

    public void Start() {

        receiver = new ServiceSearcherReceiver();
        filter = new IntentFilter();
        filter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        filter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);

        this.context.registerReceiver(receiver, filter);

        serviceListener = new WifiP2pManager.DnsSdServiceResponseListener() {

            public void onDnsSdServiceAvailable(String instanceName, String serviceType, WifiP2pDevice device) {

                debug_print("Found Service, :" + instanceName + ", type" + serviceType + ":");

                if (serviceType.startsWith(WifiBase.SERVICE_TYPE)) {
                    boolean addService = true;
                    for (int i=0; i<myServiceList.size(); i++) {
                        if(myServiceList.get(i).deviceAddress.equals(device.deviceAddress)){
                            addService = false;
                        }
                    }
                    if(addService) {
                        myServiceList.add(new ServiceItem(instanceName, serviceType, device.deviceAddress,device.deviceName));
                    }


                } else {
                    debug_print("Not our Service, :" + WifiBase.SERVICE_TYPE + "!=" + serviceType + ":");
                }

                ServiceDiscoveryTimeOutTimer.cancel();

                peerDiscoveryTimer.cancel();
                peerDiscoveryTimer.start();
            }
        };

        p2p.setDnsSdResponseListeners(channel, serviceListener, null);

        // to kick in the system a bit more
//        startPeerDiscovery();
        startServiceDiscovery();

    }

    public void Stop() {
        this.context.unregisterReceiver(receiver);
        ServiceDiscoveryTimeOutTimer.cancel();
        peerDiscoveryTimer.cancel();
        stopDiscovery();
    }

    private void startServiceDiscovery() {

        myServiceState = ServiceState.DiscoverService;
        WifiP2pDnsSdServiceRequest request = WifiP2pDnsSdServiceRequest.newInstance(WifiBase.SERVICE_TYPE);
        final Handler handler = new Handler();
        p2p.addServiceRequest(channel, request, new WifiP2pManager.ActionListener() {

            public void onSuccess() {
                debug_print("Added service request");
                handler.postDelayed(new Runnable() {
                    //There are supposedly a possible race-condition bug with the service discovery
                    // thus to avoid it, we are delaying the service discovery start here
                    public void run() {
                        p2p.discoverServices(channel, new WifiP2pManager.ActionListener() {
                            public void onSuccess() {
                                myServiceList.clear();
                                if(callback != null) {
                                    callback.StartedServiceDiscovery();
                                }
                                debug_print("Started service discovery");
                                myServiceState = ServiceState.DiscoverService;
                            }
                            public void onFailure(int reason) {
                                stopDiscovery();
                                myServiceState = ServiceState.NONE;
                                if(callback != null) {
                                    callback.ServiceStartError(reason);
                                }
                                debug_print("Starting service discovery failed, error code " + reason);
                                //lets try again after 10 minute time-out !
                                ServiceDiscoveryTimeOutTimer.cancel();
                                ServiceDiscoveryTimeOutTimer.start();
                            }
                        });
                    }
                }, 1000);
            }

            public void onFailure(int reason) {
                myServiceState = ServiceState.NONE;
                debug_print("Adding service request failed, error code " + reason);
                // No point starting service discovery
                if(callback != null) {
                    callback.AddReqStartError(reason);
                }

                //lets try again after 10 minute time-out !
                ServiceDiscoveryTimeOutTimer.cancel();
                ServiceDiscoveryTimeOutTimer.start();
            }
        });

    }

    private void startPeerDiscovery() {
        p2p.discoverPeers(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {
                if(callback != null) {
                    callback.PeerstartNow();
                }
                myServiceState = ServiceState.DiscoverPeer;
                debug_print("Started peer discovery");
            }
            public void onFailure(int reason) {
                myServiceState = ServiceState.NONE;
                debug_print("Starting peer discovery failed, error code " + reason);
                if(callback != null) {
                    callback.PeerStartError(reason);
                }
                //lets try again after 10 minute time-out !
                ServiceDiscoveryTimeOutTimer.cancel();
                ServiceDiscoveryTimeOutTimer.start();
            }
        });
    }

    private void stopDiscovery() {
        p2p.clearServiceRequests(channel, new WifiP2pManager.ActionListener() {
            public void onSuccess() {debug_print("Cleared service requests");}
            public void onFailure(int reason) {debug_print("Clearing service requests failed, error code " + reason);}
        });
    }
    private void debug_print(String buffer) {
        Log.i("Service searcher", buffer);
    }
    private class ServiceSearcherReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if(WIFI_P2P_PEERS_CHANGED_ACTION.equals(action))
            {   // only used in test-reporting to see what happens in here
                if(callback != null) {
                    callback.PeerChangedEvent();
                }
            } else if(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION.equals(action)) {

                int state = intent.getIntExtra(WifiP2pManager.EXTRA_DISCOVERY_STATE, WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                String persTatu = "Discovery state changed to ";

                if(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED){
                    if(callback != null) {
                        callback.PeerDiscoveryStopped();
                    }
                    persTatu = persTatu + "Stopped.";

                    //lets try keeping this alive
                    startPeerDiscovery();


                }else if(state == WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED){
                    persTatu = persTatu + "Started.";
                }else{
                    persTatu = persTatu + "unknown  " + state;
                }
                debug_print(persTatu);
            }
        }
    }
}