package uk.ncl.giacomobergami.components.sdn_routing;

import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Table;
import org.cloudbus.cloudsim.sdn.Link;
import org.cloudbus.cloudsim.sdn.NetworkNIC;
import org.cloudbus.cloudsim.sdn.SDNHost;
import org.cloudbus.osmosis.core.Flow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class MaximumFlowRoutingPolicy extends SDNRoutingPolicy{
    @Override @Deprecated
    public void updateSDNNetworkGraph() {
        throw new RuntimeException("Unexpected method call!");
    }

    Table<Integer, Integer, List<NetworkNIC>> table = HashBasedTable.create();
    Table<Integer, Integer, List<Link>> linkTable = HashBasedTable.create();
    Table<String, String, List<NetworkNIC>> table2 = HashBasedTable.create();
    Table<String, String, List<Link>> linkTable2 = HashBasedTable.create();

    @Override
    public List<NetworkNIC> buildRoute(NetworkNIC srcHost,
                                       NetworkNIC destHost,
                                       Flow pkt) {
        var attempt = table.get(pkt.getOrigin(), pkt.getDestination());
        if (attempt != null)
            return attempt;
        attempt = table2.get(srcHost.getName(), destHost.getName());
        if ((attempt == null) || (attempt.isEmpty()))
            throw new RuntimeException("ERROR: path was unexpectedly missing!");
        table.put(pkt.getOrigin(), pkt.getDestination(), attempt);
        linkTable.put(pkt.getOrigin(), pkt.getDestination(), linkTable2.get(srcHost.getName(), destHost.getName()));
        return attempt;
    }

    @Override
    public List<NetworkNIC> getRoute(int source, int dest) {
        return table.get(source, dest);
    }

    @Override
    public List<Link> getLinks(int source, int dest) {
        return linkTable.get(source, dest);
    }

    public void setNewPaths(Collection<List<String>> value) {
        table.clear();
        table2.clear();
        linkTable.clear();
        linkTable2.clear();
        for (var path : value) {
            List<Link> linkList = new ArrayList<>();
            var ls = path.stream().map(this::inefficientNodeByName).collect(Collectors.toList());
            for (int i = 0, N = ls.size()-1; i<N; i++) {
                var srcNode = ls.get(i);
                var destNode = ls.get(i+1);
                List<Link> links = topology.getNodeToNodeLinks(ls.get(i), ls.get(i+1));
                if ((links == null) || (links.size() < 1)) {
                    throw new RuntimeException("ERROR: expected link between " + ls.get(i)+" and "+ls.get(i+1));
                }
                /*
                 * From LoadBalancing code:
                 * Sometimes two nodes are connected via two links; therefore, find the max BW among the links!
                 */
                int numberChannel = 0;
                double bw = 0;
                Link linkWithHighestBW = null;
                for(Link l : links){
                    numberChannel = l.getChannelCount();
                    if (numberChannel ==0 || srcNode instanceof SDNHost || destNode instanceof SDNHost){ // i think you may need to look the logic again!
                        numberChannel = 1; // we cannot divide by 0
                    } else {
                        numberChannel++; // 1 for exisiting one , and one for this one
                    }
                    double currentBw = l.getBw()/numberChannel;
                    if(currentBw > bw){
                        // link bw does not change, instead you need to get the bw and number of channel on the link
                        bw = currentBw;
                        linkWithHighestBW = l;
                    }
                }
                linkList.add(linkWithHighestBW);
            }
            Collections.reverse(ls);
            Collections.reverse(linkList);
            linkTable2.put(path.get(0), path.get(path.size()-1), linkList);
            table2.put(path.get(0), path.get(path.size()-1), ls);
        }
    }
}
