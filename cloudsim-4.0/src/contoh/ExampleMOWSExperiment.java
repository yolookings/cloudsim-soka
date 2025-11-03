package contoh;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.*;
import java.io.*;
import java.util.*;

public class ExampleMOWSExperiment {

 static final int NUM_DATACENTER = 6;
 static final int HOSTS_PER_DATACENTER = 3;
 static final int VMS_PER_HOST = 3;
 static final int POWER_PER_HOST = 200;

 // Host specs
 static final int HOST_RAM = 6144; // MB
 static final long HOST_STORAGE = 1_000_000L;
 static final int HOST_BW = 10000;
 static final int HOST_PE = 1;
 static final int HOST_PE_MIPS = 6000;
 static final double HOST_COST = 3.0;

 // VM specs
 static final int VM_RAM = 512;
 static final long VM_STORAGE = 10000L;
 static final int VM_BW = 1000;
 static final int VM_MIPS = 1000;
 static final int VM_PES = 1;
 static final String VMM = "Xen";

 // Cloudlet default file/output size
 static final int CLOUDLET_FILESIZE = 300;
 static final int CLOUDLET_OUTPUTSIZE = 300;

 // Scheduler type (as requested)
 static final String CLOUDLET_SCHEDULER = "TimeShared"; // CloudletSchedulerTimeShared

 // Experiment counts (1000 .. 10000 step 1000)
 static final int[] TASK_COUNTS = {1000,2000,3000,4000,5000,6000,7000,8000,9000,10000};

 // Random seed for reproducibility
 static final long SEED = 12345L;
 static final Random RAND = new Random(SEED);

 public static void main(String[] args) {
     try {
         // Output CSV header
         String csvOut = "scenario,taskCount,totalCpuTime,totalWaitTime,avgStartTime,avgExecutionTime,avgFinishTime,throughput,makespan,imbalanceDegree,resourceUtilization,totalEnergy\n";
         // We'll run baseline and MOWS for each task count
         for (int tasks : TASK_COUNTS) {
             // Baseline: random assignment
             String baselineRes = runExperiment(tasks, false);
             csvOut += baselineRes + "\n";

             // MOWS
             String mowsRes = runExperiment(tasks, true);
             csvOut += mowsRes + "\n";

             // quick status
             System.out.printf("Finished experiments for tasks=%d%n", tasks);
         }

         // write CSV file
         String outFile = System.getProperty("user.dir") + "/outputs/mows_experiment_results.csv";
         System.out.println("CSV saved at: " + outFile);

         try (FileWriter fw = new FileWriter(outFile)) {
             fw.write(csvOut);
         }
         System.out.println("All experiments completed. CSV written to: " + outFile);
     } catch (Exception e) {
         e.printStackTrace();
     }
 }

 
 private static String runExperiment(int taskCount, boolean useMOWS) throws Exception {
     // 1. init CloudSim
     int numUser = 1;
     Calendar calendar = Calendar.getInstance();
     boolean traceFlag = false;
     CloudSim.init(numUser, calendar, traceFlag);

     // 2. create datacenters
     List<Datacenter> datacenters = new ArrayList<>();
     for (int d = 0; d < NUM_DATACENTER; d++) {
         datacenters.add(createDatacenter("Datacenter_" + d));
     }

     // 3. create broker
     DatacenterBroker broker = createBroker();
     int brokerId = broker.getId();

     // 4. create VMs (collect all VMs in vmList)
     List<Vm> vmList = new ArrayList<>();
     int vmIdCounter = 0;
     for (Datacenter dc : datacenters) {
         // For simplicity: create VMs per datacenter equal to HOSTS_PER_DATACENTER * VMS_PER_HOST
         int vmCountForDC = HOSTS_PER_DATACENTER * VMS_PER_HOST;
         for (int i = 0; i < vmCountForDC; i++) {
             Vm vm = new Vm(vmIdCounter++, brokerId, VM_MIPS, VM_PES, VM_RAM, VM_BW, VM_STORAGE, VMM, new CloudletSchedulerTimeShared());
             vmList.add(vm);
         }
     }
     // debug
     System.out.printf("Experiment (useMOWS=%b): created %d VMs, %d datacenters%n", useMOWS, vmList.size(), datacenters.size());
     broker.submitVmList(vmList);

     // 5. create cloudlets
     List<Cloudlet> cloudletList = createCloudletList(brokerId, vmList, 1, taskCount);

     // 6. scheduling BEFORE submitting cloudlets:
     if (useMOWS) {
         scheduleWithMOWS(cloudletList, vmList);
     } else {
         randomAssignCloudlets(cloudletList, vmList);
     }

     // submit cloudlets
     broker.submitCloudletList(cloudletList);

     // 7. start simulation
     CloudSim.startSimulation();
     List<Cloudlet> newList = broker.getCloudletReceivedList();
     CloudSim.stopSimulation();

     // 8. collect metrics
     double totalCpuTime = 0.0;
     double totalWaitTime = 0.0;
     double sumStart = 0.0;
     double sumExec = 0.0;
     double sumFinish = 0.0;
     double makespan = 0.0;
     Map<Integer, Double> vmExecSums = new HashMap<>(); // vmId -> sum exec time

     for (Cloudlet cl : newList) {
         if (cl.getStatus() == Cloudlet.SUCCESS) {
             double exec = cl.getActualCPUTime(); // CPU time
             double start = cl.getExecStartTime();
             double finish = cl.getFinishTime();
             double submitTime = cl.getSubmissionTime(); // likely 0
             double wait = Math.max(0.0, start - submitTime);

             totalCpuTime += exec;
             totalWaitTime += wait;
             sumStart += start;
             sumExec += exec;
             sumFinish += finish;
             makespan = Math.max(makespan, finish);

             int vmId = cl.getVmId();
             vmExecSums.put(vmId, vmExecSums.getOrDefault(vmId, 0.0) + exec);
         }
     }

     int completed = newList.size();
     double avgStart = completed>0 ? sumStart / completed : 0;
     double avgExec = completed>0 ? sumExec / completed : 0;
     double avgFinish = completed>0 ? sumFinish / completed : 0;
     double throughput = makespan>0 ? ((double)completed / makespan) : 0.0;

     // Imbalance degree: (maxExec - minExec) / avgExecPerVM
     double maxExec = 0.0, minExec = Double.POSITIVE_INFINITY, sumPerVM = 0.0;
     for (double v : vmExecSums.values()) {
         maxExec = Math.max(maxExec, v);
         minExec = Math.min(minExec, v);
         sumPerVM += v;
     }
     int vmCount = vmList.size();
     double avgPerVM = vmCount>0 ? sumPerVM / vmCount : 0;
     if (vmExecSums.size() == 0) {
         minExec = 0.0;
     } else if (minExec==Double.POSITIVE_INFINITY) minExec = 0.0;
     double imbalanceDegree = avgPerVM>0 ? (maxExec - minExec) / avgPerVM : 0.0;

     // resource utilization: sum actual CPU time / (numVM * makespan)
     double resourceUtilization = (vmCount>0 && makespan>0) ? (totalCpuTime / (vmCount * makespan)) : 0.0;

     // energy estimate: POWER_PER_HOST * totalHosts * makespan
     int totalHosts = NUM_DATACENTER * HOSTS_PER_DATACENTER;
     double totalEnergy = POWER_PER_HOST * totalHosts * makespan; // units: W * s (approx Joules)

     // Build CSV row:
     String scenario = useMOWS ? "MOWS" : "Baseline_Random";
     String row = String.format(Locale.US,
             "%s,%d,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.6f,%.6f,%.4f",
             scenario, taskCount,
             totalCpuTime, totalWaitTime, avgStart, avgExec, avgFinish, throughput, makespan,
             imbalanceDegree, resourceUtilization, totalEnergy
     );

     // Print summary to console
     System.out.printf("%s | tasks=%d | completed=%d | makespan=%.4f | avgExec=%.4f | throughput=%.4f%n",
             scenario, taskCount, completed, makespan, avgExec, throughput);

     return row;
 }

 // create datacenter
 private static Datacenter createDatacenter(String name) throws Exception {
     List<Host> hostList = new ArrayList<>();
     int hostIdBase = hostList.size();

     // Create hosts for datacenter: HOSTS_PER_DATACENTER
     for (int i = 0; i < HOSTS_PER_DATACENTER; i++) {
         List<Pe> peList = new ArrayList<>();
         peList.add(new Pe(HOST_PE, new PeProvisionerSimple(HOST_PE_MIPS)));

         int hostId = i;
         Host host = new Host(
                 hostId,
                 new RamProvisionerSimple(HOST_RAM),
                 new BwProvisionerSimple(HOST_BW),
                 HOST_STORAGE,
                 peList,
                 new VmSchedulerTimeShared(peList)
         );
         hostList.add(host);
     }

     String arch = "x86";
     String os = "Linux";
     String vmm = VMM;
     double timeZone = 10.0;
     double cost = HOST_COST;
     double costPerMem = 0.05;
     double costPerStorage = 0.1;
     double costPerBw = 0.1;
     DatacenterCharacteristics characteristics = new DatacenterCharacteristics(
             arch, os, vmm, hostList, timeZone, cost, costPerMem, costPerStorage, costPerBw
     );
     Datacenter datacenter = new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), new LinkedList<Storage>(), 0);
     return datacenter;
 }

 private static DatacenterBroker createBroker() throws Exception {
     return new DatacenterBroker("Broker_" + UUID.randomUUID().toString().substring(0,5));
 }

 private static List<Cloudlet> createCloudletList(int brokerId, List<Vm> vmList, int pesNumber, int cloudletAmount) {
     List<Cloudlet> cloudletList = new ArrayList<>();
     UtilizationModelFull utilModel = new UtilizationModelFull();

     // Try to read dataset file "RandSimple{n}.txt" if present; else fallback to random lengths
     List<Integer> lengths = tryReadDatasetLengths(cloudletAmount);

     for (int i = 0; i < cloudletAmount; i++) {
         long length = lengths.get(i);
         Cloudlet cloudlet = new Cloudlet(i, length, pesNumber, CLOUDLET_FILESIZE, CLOUDLET_OUTPUTSIZE, utilModel, utilModel, utilModel);
         cloudlet.setUserId(brokerId);
         // set vmId later by scheduler
         cloudletList.add(cloudlet);
     }
     return cloudletList;
 }

 private static List<Integer> tryReadDatasetLengths(int expectedCount) {
	 String filename = "./datasets/randomSimple/RandSimple" + expectedCount + ".txt";
     List<Integer> lens = new ArrayList<>();
     try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
         String line;
         while ((line = br.readLine()) != null && lens.size() < expectedCount) {
             line = line.trim();
             if (line.isEmpty()) continue;
             try {
                 int v = Integer.parseInt(line);
                 lens.add(v);
             } catch (NumberFormatException e) {
                 // skip
             }
         }
         // if file had fewer lines, pad with random lengths
         while (lens.size() < expectedCount) {
             lens.add(5000 + RAND.nextInt(15001)); // 5000..20000
         }
         System.out.println("Loaded dataset file: " + filename + " with " + lens.size() + " lengths.");
         return lens;
     } catch (IOException e) {
         // fallback: generate random lengths
         for (int i = 0; i < expectedCount; i++) {
             lens.add(5000 + RAND.nextInt(15001)); // 5000..20000
         }
         System.out.println("Dataset file not found (" + filename + "). Using random lengths as fallback.");
         return lens;
     }
 }

 /**
  * Baseline: assign each cloudlet randomly to a VM
  */
 private static void randomAssignCloudlets(List<Cloudlet> cloudlets, List<Vm> vms) {
     for (Cloudlet cl : cloudlets) {
         Vm vm = vms.get(RAND.nextInt(vms.size()));
         cl.setVmId(vm.getId());
     }
 }

 //mows scheduler
 private static void scheduleWithMOWS(List<Cloudlet> cloudlets, List<Vm> vms) {
     // constants for normalization (tune if needed)
     double maxTaskLength = 20000.0;
     double maxVmMips = 6000.0;
     double maxCommSize = 2000.0;
     double maxVmBw = 5000.0;

     double WPer = 0.7;
     double WSec = 0.3;

     for (Cloudlet cl : cloudlets) {
         double bestDD = Double.MAX_VALUE;
         Vm bestVm = null;

         double taskCompNorm = Math.min(1.0, cl.getCloudletLength() / maxTaskLength);
         double taskCommNorm = Math.min(1.0, (double)CLOUDLET_FILESIZE / maxCommSize); 

         double taskSecDemand = RAND.nextDouble(); // 0..1

         for (Vm vm : vms) {
             double vmMipsNorm = Math.min(1.0, (double)vm.getMips() / maxVmMips);
             double vmBwNorm = Math.min(1.0, (double)vm.getBw() / maxVmBw);
             double vmSecurity = RAND.nextDouble(); // pseudo security capability

             double CD = Math.max(0.0, taskCompNorm - vmMipsNorm);
             double TD = Math.max(0.0, taskCommNorm - vmBwNorm);
             double SD = Math.abs(taskSecDemand - vmSecurity);

             double dd = WPer * (CD + TD) + WSec * SD;

             if (dd < bestDD) {
                 bestDD = dd;
                 bestVm = vm;
             }
         }

         if (bestVm != null) {
             cl.setVmId(bestVm.getId());
         } else {
             // fallback
             cl.setVmId(vms.get(0).getId());
         }
     }
 }
}
