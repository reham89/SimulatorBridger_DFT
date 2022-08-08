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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import org.cloudbus.agent.AgentBroker;
import org.cloudbus.agent.CentralAgent;
import org.cloudbus.cloudsim.Cloudlet;
import org.cloudbus.cloudsim.DatacenterBroker;
import org.cloudbus.cloudsim.UtilizationModelFull;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.MainEventManager;
import org.cloudbus.cloudsim.core.CloudSimTags;
import org.cloudbus.cloudsim.core.SimEvent;
import org.cloudbus.cloudsim.edge.core.edge.EdgeLet;
import uk.ncl.giacomobergami.components.iot.IoTDevice;
import uk.ncl.giacomobergami.components.loader.GlobalConfigurationSettings;
import uk.ncl.giacomobergami.components.mel_routing.MELRoutingPolicy;
import uk.ncl.giacomobergami.utils.asthmatic.WorkloadCSV;

/**
 * 
 * @author Khaled Alwasel
 * @contact kalwasel@gmail.com
 * @since IoTSim-Osmosis 1.0
 * 
**/

public class OsmoticBroker extends DatacenterBroker {

	public EdgeSDNController edgeController;
	public List<Cloudlet> edgeletList = new ArrayList<>();
	public List<OsmoticAppDescription> appList;
	public Map<String, Integer> iotDeviceNameToId = new HashMap<>();
	public Map<Integer, List<? extends Vm>> mapVmsToDatacenter  = new HashMap<>();
	public static int brokerID;
	public Map<String, Integer> iotVmIdByName = new HashMap<>();
	public static List<WorkflowInfo> workflowTag = new ArrayList<>();
	public List<OsmoticDatacenter> datacenters = new ArrayList<>();
	private final AtomicInteger edgeLetId;

	//private Map<String, Integer> roundRobinMelMap = new HashMap<>();

	public CentralAgent osmoticCentralAgent;
	private AtomicInteger flowId;

	public OsmoticBroker(String name, AtomicInteger edgeLetId, AtomicInteger flowId) {
		super(name);
		this.edgeLetId = edgeLetId;
		this.flowId = flowId;
		this.appList = new ArrayList<>();		
		brokerID = this.getId();
	}

	public EdgeSDNController getEdgeSDNController() {
		return edgeController;
	}
	public void setEdgeSDNController(EdgeSDNController controller) {
		this.edgeController = controller;
	}

	@Override
	public void startEntity() {
		super.startEntity();
	}

	@Override
	public void processEvent(SimEvent ev) {
		//Update simulation time in the AgentBroker
		AgentBroker.getInstance().updateTime(MainEventManager.clock());

		//Execute MAPE loop at time interval
		AgentBroker.getInstance().executeMAPE(MainEventManager.clock());

		switch (ev.getTag()) {
		case CloudSimTags.RESOURCE_CHARACTERISTICS_REQUEST:
			this.processResourceCharacteristicsRequest(ev);
			break;
			
		case CloudSimTags.RESOURCE_CHARACTERISTICS:
			this.processResourceCharacteristics(ev);
			break;
			
		case CloudSimTags.VM_CREATE_ACK:
			this.processVmCreate(ev);			
			break;
			
		case OsmoticTags.GENERATE_OSMESIS:
			generateIoTData(ev);
			break;
			
		case OsmoticTags.Transmission_ACK:
			askMelToProccessData(ev);
			break;
			
		case CloudSimTags.CLOUDLET_RETURN:
			processCloudletReturn(ev);
			break;
			
		case OsmoticTags.Transmission_SDWAN_ACK:
			askCloudVmToProccessData(ev);
			break;
				
		case CloudSimTags.END_OF_SIMULATION: // just printing
			this.shutdownEntity();
			break;

		case OsmoticTags.ROUTING_MEL_ID_RESOLUTION:
			this.melResolution(ev);

		default:
			break;
		}
	}

	MELRoutingPolicy melRouting;
	public MELRoutingPolicy getMelRouting() {
		return melRouting;
	}
	public void setMelRouting(MELRoutingPolicy melRouting) {
		this.melRouting = melRouting;
	}

	private void melResolution(SimEvent ev) {
		Flow flow = (Flow) ev.getData();
		String melName = flow.getAppNameDest();
		int mel_id = -1;

		if (melRouting.test(melName)){
			// Using a policy for determining the next MEL
			String melInstanceName = melRouting.apply(melName, this);
			flow.setAppNameDest(melInstanceName);
			mel_id = getVmIdByName(melInstanceName); //name of VM

			//dynamic mapping to datacenter
			int edgeDatacenterId = this.getDatacenterIdByVmId(mel_id);
			flow.setDatacenterId(edgeDatacenterId);
			flow.setDatacenterName(this.getDatacenterNameById(edgeDatacenterId));
			flow.getWorkflowTag().setSourceDCName(this.getDatacenterNameById(edgeDatacenterId));
		} else {
			mel_id = getVmIdByName(melName); //name of VM

			//dynamic mapping to datacenter
			int edgeDatacenterId = this.getDatacenterIdByVmId(mel_id);
			flow.setDatacenterId(edgeDatacenterId);
			flow.setDatacenterName(this.getDatacenterNameById(edgeDatacenterId));
			flow.getWorkflowTag().setSourceDCName(this.getDatacenterNameById(edgeDatacenterId));
		}

		flow.setDestination(mel_id);
		sendNow(flow.getDatacenterId(), OsmoticTags.TRANSMIT_IOT_DATA, flow);
	}

	protected void processCloudletReturn(SimEvent ev)
	{
		Cloudlet cloudlet = (Cloudlet) ev.getData();						
		getCloudletReceivedList().add(cloudlet);
		EdgeLet edgeLet = (EdgeLet) ev.getData();	
		if(!edgeLet.getIsFinal()){	
			askMelToSendDataToCloud(ev);			
			return;
		}	
		edgeLet.getWorkflowTag().setFinishTime(MainEventManager.clock());
	}
	
	private void askMelToProccessData(SimEvent ev) {
		Flow flow = (Flow) ev.getData();		
		EdgeLet edgeLet = generateEdgeLet(flow.getOsmesisEdgeletSize());
		edgeLet.setVmId(flow.getDestination());
		edgeLet.setCloudletLength(flow.getOsmesisEdgeletSize());
		edgeLet.isFinal(false);
		edgeletList.add(edgeLet);
		int appId = flow.getOsmesisAppId();
		edgeLet.setOsmesisAppId(appId);
		edgeLet.setWorkflowTag(flow.getWorkflowTag());
		edgeLet.getWorkflowTag().setEdgeLet(edgeLet);		
		this.setCloudletSubmittedList(edgeletList);		
		sendNow(flow.getDatacenterId(), CloudSimTags.CLOUDLET_SUBMIT, edgeLet);
	}
	
	private EdgeLet generateEdgeLet(long length) {				
		long fileSize = 30;
		long outputSize = 1;		
		EdgeLet edgeLet = new EdgeLet(edgeLetId.getAndIncrement(), length, 1, fileSize, outputSize, new UtilizationModelFull(), new UtilizationModelFull(),
				new UtilizationModelFull());			
		edgeLet.setUserId(this.getId());
//		LegacyTopologyBuilder.edgeLetId++;
		return edgeLet;
	}	

	protected void askCloudVmToProccessData(SimEvent ev) {
		Flow flow = (Flow) ev.getData();		
		int appId = flow.getOsmesisAppId();		
		int dest = flow.getDestination();				
		OsmoticAppDescription app = getAppById(appId);
		long length = app.getOsmesisCloudletSize();		
		EdgeLet cloudLet =	generateEdgeLet(length);							
		cloudLet.setVmId(dest);
		cloudLet.isFinal(true);			
		edgeletList.add(cloudLet);		
		cloudLet.setOsmesisAppId(appId);
		cloudLet.setWorkflowTag(flow.getWorkflowTag());
		cloudLet.getWorkflowTag().setCloudLet(cloudLet);		
		this.setCloudletSubmittedList(edgeletList);		
		cloudLet.setUserId(OsmoticBroker.brokerID);
		this.setCloudletSubmittedList(edgeletList);
		int dcId = getDatacenterIdByVmId(dest);
		sendNow(dcId, CloudSimTags.CLOUDLET_SUBMIT, cloudLet);
	}

	private void askMelToSendDataToCloud(SimEvent ev) {
		EdgeLet edgeLet = (EdgeLet) ev.getData();		
		int osmesisAppId = edgeLet.getOsmesisAppId();		
		OsmoticAppDescription app = getAppById(osmesisAppId);
		int sourceId = edgeLet.getVmId(); // MEL or VM  			
		int destId = this.getVmIdByName(app.getVmName()); // MEL or VM
		int id = flowId.getAndIncrement();
		int melDataceneter = this.getDatacenterIdByVmId(sourceId);		
		Flow flow  = new Flow(app.getMELName(), app.getVmName(), sourceId , destId, id, null);									
		flow.setAppName(app.getAppName());
		flow.addPacketSize(app.getMELOutputSize());
		flow.setSubmitTime(MainEventManager.clock());
		flow.setOsmesisAppId(osmesisAppId);				
		flow.setWorkflowTag(edgeLet.getWorkflowTag());
		flow.getWorkflowTag().setEdgeToCloudFlow(flow);		
//		LegacyTopologyBuilder.flowId++;
		sendNow(melDataceneter, OsmoticTags.BUILD_ROUTE, flow);
	}	

	private OsmoticAppDescription getAppById(int osmesisAppId) {
		OsmoticAppDescription osmesis = null;
		for(OsmoticAppDescription app : this.appList){
			if(app.getAppID() == osmesisAppId){
				osmesis = app;
			}
		}
		return osmesis;
	}

	
	public void submitVmList(List<? extends Vm> list, int datacenterId) {		
		mapVmsToDatacenter.put(datacenterId, list);
		getVmList().addAll(list);
	}
	
	protected void createVmsInDatacenter(int datacenterId) {		
		int requestedVms = 0;
		List<? extends Vm> vmList = mapVmsToDatacenter.get(datacenterId);
		if(vmList != null){
			for (int i = 0; i < vmList.size(); i++) {
				Vm vm = vmList.get(i);		
					sendNow(datacenterId, CloudSimTags.VM_CREATE_ACK, vm);				
					requestedVms++;
			}
		}
		getDatacenterRequestedIdsList().add(datacenterId);
		setVmsRequested(requestedVms);
		setVmsAcks(0);
	}

	@Override
	protected void processOtherEvent(SimEvent ev) {

	}
	
	@Override
	public void processVmCreate(SimEvent ev) {
		super.processVmCreate(ev);
		if (allRequestedVmsCreated()) {		
			for(OsmoticAppDescription app : this.appList){				
				int iotDeviceID = getiotDeviceIdByName(app.getIoTDeviceName());

				//This is necessary for osmotic flow abstract routing.
				int melId=-1;
				if (!melRouting.test(app.getMELName())){
					melId = getVmIdByName(app.getMELName());
				}
				int vmIdInCloud = this.getVmIdByName(app.getVmName());
				app.setIoTDeviceId(iotDeviceID);
				app.setMelId(melId);				
				int edgeDatacenterId = this.getDatacenterIdByVmId(melId);		
				app.setEdgeDcId(edgeDatacenterId);
				app.setEdgeDatacenterName(this.getDatacenterNameById(edgeDatacenterId));				
				int cloudDatacenterId = this.getDatacenterIdByVmId(vmIdInCloud);				
				app.setCloudDcId(cloudDatacenterId);
				app.setCloudDatacenterName(this.getDatacenterNameById(cloudDatacenterId));				
				if(app.getAppStartTime() == -1){
					app.setAppStartTime(MainEventManager.clock());
				}
				double delay = app.getDataRate()+app.getStartDataGenerationTime();
				send(this.getId(), delay, OsmoticTags.GENERATE_OSMESIS, app);
			}
		}
	}	

	private void generateIoTData(SimEvent ev){
		OsmoticAppDescription app = (OsmoticAppDescription) ev.getData();
		if((MainEventManager.clock() >= app.getStartDataGenerationTime()) &&
				(MainEventManager.clock() < app.getStopDataGenerationTime()) &&
				!app.getIsIoTDeviceDied()){
			sendNow(app.getIoTDeviceId(), OsmoticTags.SENSING, app);
			send(this.getId(), app.getDataRate(), OsmoticTags.GENERATE_OSMESIS, app);
		}
	}
			
	private boolean allRequestedVmsCreated() {
		return this.getVmsCreatedList().size() == getVmList().size() - getVmsDestroyed();
	}

	public void submitOsmesisApps(List<OsmoticAppDescription> appList) {
		this.appList = appList;		
	}

	public List<OsmoticAppDescription> submitWorkloadCSVApps(List<WorkloadCSV> appList) {
		this.appList = appList.stream().map(GlobalConfigurationSettings::asLegacyApp).collect(Collectors.toList());
		return this.appList;
	}

	public int getiotDeviceIdByName(String melName){
		return this.iotDeviceNameToId.get(melName);
	}

	public void addIoTDevices(List<IoTDevice> devices) {
		for(IoTDevice device : devices){
			iotDeviceNameToId.put(device.getName(), device.getId());
		}
	}
	
	public void mapVmNameToId(Map<String, Integer> melNameToIdList) {
	this.iotVmIdByName.putAll(melNameToIdList);		
	}
	
	public int getVmIdByName(String name){
		return this.iotVmIdByName.get(name);
	}


	public void setDatacenters(List<OsmoticDatacenter> osmesisDatacentres) {
		this.datacenters = osmesisDatacentres;		
	}
	
	private int getDatacenterIdByVmId(int vmId){
		int dcId = 0;
		for(OsmoticDatacenter dc :datacenters){
			for(Vm vm : dc.getVmList()){
				if(vm.getId() == vmId){
					dcId = dc.getId();					
				}
			}
		}
		return dcId;
	}
	
	private String getDatacenterNameById(int id){
		String name = "";
		for(OsmoticDatacenter dc :datacenters){
			if(dc.getId() == id){
				name = dc.getName();
			}
		}
		return name;
	}

	public void addIoTDevice(IoTDevice device) {
		iotDeviceNameToId.put(device.getName(), device.getId());
	}
}
