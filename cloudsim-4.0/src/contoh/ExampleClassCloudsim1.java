package contoh;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.provisioners.*;
import org.cloudbus.cloudsim.core.CloudSim;

import java.util.*;

public class ExampleClassCloudsim1 {

	static class DatacenterInfo {
		Datacenter datacenter;
		DatacenterCharacteristics characteristics;
		
		DatacenterInfo(Datacenter datacenterm, DatacenterCharacteristics characteristics) {
			this.datacenter = datacenter;
			this.characteristics = characteristics;
		}
	}
	
	public static void main(String[] args) {
		// TODO Auto-generated method stub
		System.out.println("This is My Cloudsim");
		
		try {
			int numUser = 1;
			Calendar calendar = Calendar.getInstance();
			boolean traceflag = false;
			
			
			CloudSim.init(numUser, calendar, traceflag);
			
			DatacenterInfo dcInfo  = createDatacenter("Datacenter_0");
			
			DatacenterBroker broker = createBroker();
			int brokerId = broker.getId();
			int pesNumber = 1;
			
			List<Vm> vmList = createVmList(
						brokerId,
						3,				// Jumlah VM
						1000, 2000,		// MIPS (min,max)
						512, 1024,		// RAM
						5000, 15000,	 // Storage
						500, 2000,		// BW
						pesNumber,
						"Xen"
					);
	
			broker.submitVmList(vmList);
			
			List<Cloudlet> cloudList = createCloudletList(
						brokerId,
						vmList,
						pesNumber,
						6,					// Jumlah Cloudlet
						5000, 20000,		// MI
						100, 500, 			// File Size
						100, 500			// Output Size
					);
			 
			broker.submitCloudletList(cloudList);
			
			CloudSim.startSimulation();
			
			List<Cloudlet> newList = broker.getCloudletReceivedList();
			
			CloudSim.stopSimulation();
			
			printCloudletList(newList, dcInfo.characteristics);
			System.out.println("Selesai ✅");
			
		} catch(Exception err) {
			System.out.println("Terjadi Error pada Simulasi");
			err.printStackTrace();
		}
		
	}
	
	private static DatacenterInfo createDatacenter(String name) {
		List<Host> hostList = new ArrayList<>();
		List<Pe> peList = new ArrayList<>();
		
		
		int mips = 10000; //million instruction per second
		peList.add(new Pe(0, new PeProvisionerSimple(mips)));
		
		int hostId = 0;
		int ram = 4096; 		// MB
		long storage = 1000000;
		int bw = 10000;
		
		Host host = new Host(hostId, new RamProvisionerSimple(ram), new BwProvisionerSimple(bw), storage, peList, new VmSchedulerTimeShared(peList));
		hostList.add(host);
		
		String arch = "x86";
		String os = "Linux";
		String vmm = "Xen";
		double timeZone = 10.0;
		double cost = 3.0; // biaya per second
		double costPerMem = 0.05;
		double costPerStorage = 0.1;
		double costPerBw = 0.1;
		
		 DatacenterCharacteristics characteristics = new DatacenterCharacteristics(arch, os, vmm, hostList, timeZone, cost, costPerMem, costPerStorage, costPerBw);
		 
		 Datacenter datacenter = null;
		 
		 try {
			 datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), new LinkedList<Storage>(), 0);
		 } catch (Exception err) {
			 err.printStackTrace();
		 }
		 
		 return new DatacenterInfo(datacenter, characteristics);
	}
	
	private static DatacenterBroker createBroker() {
		DatacenterBroker broker = null;
		
		try {
			broker = new DatacenterBroker("Brokerr");
		} catch (Exception err) {
			err.printStackTrace();
		}
		
		return broker;
	}
	
	private static List<Vm> createVmList(
				int brokerId,
				int vmAmount,
				int minMips, int maxMips,
				int minRam, int maxRam,
				long minStorage, long maxStorage,
				long minBw, long maxBw,
				int pesNumber,
				String vmm
			){
		List<Vm> vmList = new ArrayList<>();
		Random rand = new Random();
		
		for(int i= 0; i < vmAmount; i++) {
			int mips = minMips + rand.nextInt(maxMips - minMips + 1);
			int ram = minRam + rand.nextInt(maxRam - minRam + 1);
			long size = minStorage + rand.nextInt((int)(maxStorage - minStorage + 1));
			long bw = minBw+ rand.nextInt((int)(maxBw - minBw + 1));
			
			Vm vm = new Vm(i, brokerId, mips, pesNumber, ram, bw, size, vmm, new CloudletSchedulerTimeShared());
			vmList.add(vm);
			
			System.out.printf("VM-%d Created | MIPS: %d | RAM: %d MB | Storage: %d | BW: %dn%n", i, mips, ram, size, bw);
			
		}
		
		return vmList;
	};
	
	private static List<Cloudlet> createCloudletList(
				int brokerId,
				List<Vm> vmList,
				int pesNumber,
				int cloudletAmount,
				long minLength, long maxLength,
				long minFileSize, long maxFileSize,
				long minOutputSize, long maxOutputSize
			){
		List<Cloudlet> cloudletList = new ArrayList<>();
		Random rand = new Random();
		UtilizationModel utilModel = new UtilizationModelFull();	
		
		for(int i = 0; i < cloudletAmount; i++) {
			long length = minLength + rand.nextInt((int)(maxLength - minLength + 1));
			long fileSize = minFileSize + rand.nextInt((int)(maxFileSize - minFileSize + 1));
			long outputSize = minOutputSize + rand.nextInt((int)(maxOutputSize- minOutputSize + 1));
			
			Cloudlet cloudlet = new Cloudlet(i, length, pesNumber, fileSize, outputSize, utilModel, utilModel, utilModel);
		
			int vmId = vmList.get(i % vmList.size()).getId();
			cloudlet.setUserId(brokerId);
			cloudlet.setVmId(vmId); 
			
			cloudletList.add(cloudlet);
			
			System.out.printf("Cloudlet-%d Created | MI: %d | FileSize: %d |  OutputSize: %d | VM: VM-%d%n", i, length, fileSize, outputSize, vmId);
		}
		return cloudletList;
	};
	
	private static void printCloudletList(List<Cloudlet> list, DatacenterCharacteristics characteristics) {
		System.out.printf("%-10s%-15s%-15s%-15s%-15s%-15s%-15s%n", "ID", "Status", "Datacenter", "VM", "Waktu Mulai", "Waktu Selesai", "Biaya");
		
		double totalCost = 0;
		for(Cloudlet cloudlet : list) {
			if(cloudlet.getStatus() == cloudlet.SUCCESS) {
				double execTime = cloudlet.getActualCPUTime();
				double cpuCost = characteristics.getCostPerSecond();
				double cost = execTime * cpuCost;
				
				totalCost += cost;
				
				System.out.printf("%-10d" , cloudlet.getCloudletId());
				System.out.printf("%-15s%-15d%-15d%-15.2f%-15.2f$%-14.2f%n", "SUCCESS", cloudlet.getResourceId(), cloudlet.getVmId(), cloudlet.getExecStartTime(), cloudlet.getFinishTime(), cost);
			}
		} 
		
		System.out.printf("%nTotal Biaya: $%.2f%n", totalCost);
	}

}
