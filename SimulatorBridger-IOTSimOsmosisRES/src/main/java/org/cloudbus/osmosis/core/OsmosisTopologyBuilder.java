/*
 * Title:        IoTSim-Osmosis 1.0
 * Description:  IoTSim-Osmosis enables the testing and validation of osmotic computing applications 
 * 			     over heterogeneous edge-cloud SDN-aware environments.
 * 
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2020, Newcastle University (UK) and Saudi Electronic University (Saudi Arabia) 
 * 
 */

package org.cloudbus.osmosis.core;

import org.cloudbus.cloudsim.DatacenterCharacteristics;
import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.Storage;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.edge.core.edge.ConfiguationEntity;
import org.cloudbus.cloudsim.edge.core.edge.ConfiguationEntity.CloudDataCenterEntity;
import org.cloudbus.cloudsim.edge.core.edge.ConfiguationEntity.EdgeDataCenterEntity;
import org.cloudbus.cloudsim.edge.core.edge.ConfiguationEntity.LogEntity;
import org.cloudbus.cloudsim.edge.core.edge.EdgeDataCenter;
import org.cloudbus.cloudsim.edge.core.edge.EdgeDevice;
import org.cloudbus.cloudsim.edge.core.edge.MEL;
import org.cloudbus.cloudsim.edge.utils.LogUtil;
import org.cloudbus.cloudsim.edge.utils.LogUtil.Level;
import org.cloudbus.cloudsim.sdn.Switch;
import org.cloudbus.cloudsim.sdn.example.policies.VmAllocationPolicyCombinedMostFullFirst;
import org.cloudbus.osmosis.core.policies.VmMELAllocationPolicyCombinedLeastFullFirst;
import org.cloudbus.res.model.pvgis.input.Fields;
import uk.ncl.giacomobergami.components.iot.IoTDevice;
import uk.ncl.giacomobergami.components.iot.IoTDeviceConfiguration;
import uk.ncl.giacomobergami.components.iot.IoTGeneratorFactory;
import uk.ncl.giacomobergami.utils.data.CSVMediator;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * 
 * @author Khaled Alwasel, Tomasz Szydlo
 * @contact kalwasel@gmail.com
 * @since IoTSim-Osmosis 1.0
 * 
**/

public class OsmosisTopologyBuilder {
	private OsmoticBroker broker;
	List<CloudDatacenter> cloudDatacentres;
	public static  List<EdgeDataCenter> edgeDatacentres;
	public static int flowId = 1;
	public static int edgeLetId = 1;
	private SDNController sdWanController;
	
	public static AtomicInteger hostId = new AtomicInteger(1);
	private static AtomicInteger vmId = new AtomicInteger(1);
	
	public SDNController getSdWanController() {
		return sdWanController;
	}
	private List<OsmoticDatacenter> osmesisDatacentres;
	  
    public OsmosisTopologyBuilder(OsmoticBroker osmesisBroker) {
    	this.broker = osmesisBroker;
    	this.osmesisDatacentres = new ArrayList<>();
	}

	public OsmosisTopologyBuilder buildTopology(File filename) {
		return buildTopology(Objects.requireNonNull(ConfiguationEntity.fromFile(Objects.requireNonNull(filename))));
	}

	public List<OsmoticDatacenter> getOsmesisDatacentres() {
		return osmesisDatacentres;
	}

    public OsmosisTopologyBuilder buildTopology(ConfiguationEntity topologyEntity) {
		List<Switch> datacenterGateways = new ArrayList<>();
		for (var x : topologyEntity.getCloudDatacenter()) {
			var y = createCloudDatacenter(x);
			var controller = y.getSdnController();
			datacenterGateways.add(controller.getGateway());
			osmesisDatacentres.add(y);
		}
		for (var x : topologyEntity.getEdgeDatacenter()) {
			var y = buildEdgeDatacenter(x);
			var controller = y.getSdnController();
			datacenterGateways.add(controller.getGateway());
			osmesisDatacentres.add(y);
		}

        initLog(topologyEntity);

        sdWanController = new SDWANController(topologyEntity.getSdwan().get(0), datacenterGateways);
		osmesisDatacentres.forEach(datacenter -> datacenter.getSdnController().setWanController(sdWanController));
        sdWanController.addAllDatacenters(osmesisDatacentres);
		return this;
    }

	private CloudDatacenter createCloudDatacenter(CloudDataCenterEntity datacentreEntity) {
		SDNController sdnController = new CloudSDNController(datacentreEntity.getControllers().get(0));
		List<Host> hostList = sdnController.getHostList();
		LinkedList<Storage> storageList = new LinkedList<>();
		var allPol = datacentreEntity.getVmAllocationPolicy();
		VmAllocationPolicyFactory vmAllocationFactory = (allPol.equals("VmAllocationPolicyCombinedFullFirst")) ?
				(hl -> new VmAllocationPolicyCombinedMostFullFirst()) :
				((allPol.equals(("VmAllocationPolicyCombinedLeastFullFirst"))) ?
						hl -> new VmMELAllocationPolicyCombinedLeastFullFirst() :
						null);
		DatacenterCharacteristics characteristics = new DatacenterCharacteristics(hostList);

		// Create Datacenter with previously set parameters
		try {
			// Why to use maxHostHandler!
			var loc_datacentre = new CloudDatacenter(datacentreEntity,
					                                 characteristics,
					                                 vmAllocationFactory.create(hostList),
					                                 storageList,
					                                 0,
					                                 sdnController);

			List<Vm> vmList = datacentreEntity
					.getVMs()
					.stream()
					.map(x -> {
						var vm = new Vm(x, this.broker, vmId);
						loc_datacentre.mapVmNameToID(vm.getId(), vm.getVmName());
						return vm;
					})
					.collect(Collectors.toList());

			this.broker.mapVmNameToId(loc_datacentre.getVmNameToIdList());
			loc_datacentre.setVmList(vmList);
			loc_datacentre.setDCAndAddVMsToSDNHosts();
			loc_datacentre.getVmAllocationPolicy().setUpVmTopology(loc_datacentre.getHosts());
			return loc_datacentre;
		} catch (Exception e) {
			e.printStackTrace();
			return null;
		}
	}

	private EdgeDataCenter buildEdgeDatacenter(EdgeDataCenterEntity edgeDCEntity) {
		if (edgeDCEntity.getControllers().size() > 1)
			throw new RuntimeException("Expected size 1 for "+edgeDCEntity.getControllers().size());

		var hostList = edgeDCEntity.getHosts()
				.stream()
				.map(x -> new EdgeDevice(hostId, x))
				.collect(Collectors.toList());

		LinkedList<Storage> storageList = new LinkedList<>();

		// 6. Finally, we need to create a PowerDatacenter object.
		EdgeDataCenter datacenter = new EdgeDataCenter(edgeDCEntity,
				hostList,
				storageList,
				edgeDCEntity.getSchedulingInterval());
		System.out.println("Edge SDN cotroller has been created");

		var MELList = edgeDCEntity.getMELEntities()
				.stream()
				.map(x -> {
					var mel = new MEL(datacenter.getId(),
							vmId, x, broker);
					datacenter.mapVmNameToID(mel.getId(), mel.getVmName());
					return mel;
				})
				.collect(Collectors.toList());
		datacenter.setVmList(MELList);

		broker.mapVmNameToId(datacenter.getVmNameToIdList());
		datacenter.getVmAllocationPolicy().setUpVmTopology(hostList);
		datacenter.getSdnController().addVmsToSDNhosts(MELList);
		var associatedEdge = edgeDCEntity.getName();


				new CSVMediator<IoTDeviceConfiguration>(IoTDeviceConfiguration.class)
						.writeAll(new File("customer.csv").getAbsoluteFile(),
								edgeDCEntity.getIoTDevices().stream().map(IoTDeviceConfiguration::fromLegacy).collect(Collectors.toList()));


		edgeDCEntity.getIoTDevices()
				.forEach(x -> {
					IoTDevice newInstance = IoTGeneratorFactory.generateFacade(x);
					if ((associatedEdge != null) && (!associatedEdge.isEmpty()))
						newInstance.setAssociatedEdge(associatedEdge);
					broker.addIoTDevice(newInstance);
				});

		return datacenter;
	}
	
	private void initLog(ConfiguationEntity conf) {
		LogEntity logEntity = conf.getLogEntity();
		boolean saveLogToFile = logEntity.isSaveLogToFile();
		if (saveLogToFile) {
			String logFilePath = logEntity.getLogFilePath();
			String logLevel = logEntity.getLogLevel();
			boolean append = logEntity.isAppend();
			LogUtil.initLog(Level.valueOf(logLevel.toUpperCase()), logFilePath, saveLogToFile, append);
		}
	}	
}