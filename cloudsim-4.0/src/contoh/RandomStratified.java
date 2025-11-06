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
    static final String CLOUDLET_SCHEDULER = "TimeShared";

    // Experiment counts (1000 .. 10000 step 1000)
    static final int[] TASK_COUNTS = {1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000};
    
    // Konstanta: Jumlah pengulangan per skenario
    static final int NUM_RUNS = 10; 

    // Random seed for reproducibility
    static final long SEED = 12345L;
    static final Random RAND = new Random(SEED);
    
    // 👇 ROUND-ROBIN: Indeks VM berikutnya yang akan dipilih
    static int nextVmIndex = 0; 

    public static void main(String[] args) {
        try {
            // Header untuk Output Detil (10 percobaan)
            String csvOut = "scenario,taskCount,run,totalCpuTime,totalWaitTime,avgStartTime,avgExecutionTime,avgFinishTime,throughput,makespan,imbalanceDegree,resourceUtilization,totalEnergy\n";

            // Header untuk Tabel Hasil Akhir (Rata-Rata)
            String finalTable = "scenario,taskCount,avgTotalCpuTime,avgTotalWaitTime,avgAvgStartTime,avgAvgExecutionTime,avgAvgFinishTime,avgThroughput,avgMakespan,avgImbalanceDegree,avgResourceUtilization,avgTotalEnergy\n";

            for (int tasks : TASK_COUNTS) {
                // Proses MOWS (10 kali pengulangan)
                String mowsResults = runMultipleExperiments(tasks, true);
                csvOut += mowsResults;
                finalTable += calculateAndAppendAverage(tasks, "MOWS", mowsResults);

                // Proses Baseline (Round-Robin, 10 kali pengulangan)
                String baselineResults = runMultipleExperiments(tasks, false);
                csvOut += baselineResults;
                finalTable += calculateAndAppendAverage(tasks, "Baseline_RoundRobin", baselineResults);

                System.out.printf("Finished experiments for tasks=%d (10 runs each)%n", tasks);
            }

            // Tulis file CSV detil (semua 100-200 baris data mentah)
            String detailFile = System.getProperty("user.dir") + "/outputs/mows_rr_experiment_details.csv";
            try (FileWriter fw = new FileWriter(detailFile)) {
                fw.write(csvOut);
            }
            System.out.println("Detailed CSV saved at: " + detailFile);

            // Tulis file CSV ringkasan (hanya rata-rata)
            String summaryFile = System.getProperty("user.dir") + "/outputs/mows_rr_experiment_summary.csv";
            try (FileWriter fw = new FileWriter(summaryFile)) {
                fw.write(finalTable);
            }
            System.out.println("Summary CSV (Rata-Rata) saved at: " + summaryFile);

            System.out.println("All experiments completed.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // --- Metode Pembantu untuk Pengulangan Eksperimen ---

    /**
     * Menjalankan eksperimen sebanyak NUM_RUNS (10 kali) dan mengembalikan semua hasil.
     */
    private static String runMultipleExperiments(int taskCount, boolean useMOWS) throws Exception {
        StringBuilder results = new StringBuilder();
        // Reset nextVmIndex sebelum setiap batch runs
        nextVmIndex = 0; 
        
        for (int run = 1; run <= NUM_RUNS; run++) {
            // CloudSim harus dihentikan dan diinisialisasi ulang untuk setiap run
            CloudSim.terminateSimulation(); 
            String runRes = runExperiment(taskCount, useMOWS, run);
            results.append(runRes).append("\n");
        }
        return results.toString();
    }

    /**
     * Menghitung rata-rata dari 10 kali pengulangan dan mengembalikannya sebagai baris CSV.
     */
    private static String calculateAndAppendAverage(int taskCount, String scenario, String runResults) {
        final int METRIC_COUNT = 10; 
        double[] totalMetrics = new double[METRIC_COUNT];
        int actualRuns = 0;

        String[] lines = runResults.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            // Format: scenario,taskCount,run,M1,M2,M3,...
            String[] parts = line.split(",");
            if (parts.length < METRIC_COUNT + 3) continue; 

            try {
                // Metrik dimulai dari indeks 3 (M1)
                for (int i = 0; i < METRIC_COUNT; i++) {
                    totalMetrics[i] += Double.parseDouble(parts[i + 3].trim());
                }
                actualRuns++;
            } catch (NumberFormatException e) {
                System.err.println("Skipping malformed line during average calculation: " + line);
            }
        }

        if (actualRuns == 0) return "";

        // Buat baris Rata-Rata
        StringBuilder avgRowBuilder = new StringBuilder();
        // Kolom pertama: Scenario (MOWS/Baseline) + Kolom kedua: taskCount
        avgRowBuilder.append(scenario).append(",").append(taskCount).append(",");
        
        for (int i = 0; i < METRIC_COUNT; i++) {
            double average = totalMetrics[i] / actualRuns;
            // Format 10 angka di belakang koma (sesuai permintaan presisi)
            avgRowBuilder.append(String.format(Locale.US, "%.10f", average)); 
            if (i < METRIC_COUNT - 1) {
                avgRowBuilder.append(",");
            }
        }
        
        return avgRowBuilder.toString() + "\n";
    }

    // --- Metode Inti CloudSim ---

    /**
     * Menjalankan satu kali eksperimen (Run ke-X).
     */
    private static String runExperiment(int taskCount, boolean useMOWS, int run) throws Exception {
        // 1. init CloudSim (Diulang setiap run)
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
            int vmCountForDC = HOSTS_PER_DATACENTER * VMS_PER_HOST;
            for (int i = 0; i < vmCountForDC; i++) {
                Vm vm = new Vm(vmIdCounter++, brokerId, VM_MIPS, VM_PES, VM_RAM, VM_BW, VM_STORAGE, VMM, new CloudletSchedulerTimeShared());
                vmList.add(vm);
            }
        }
        broker.submitVmList(vmList);

        // 5. create cloudlets
        List<Cloudlet> cloudletList = createCloudletList(brokerId, vmList, 1, taskCount);

        // 6. scheduling:
        if (useMOWS) {
            scheduleWithMOWS(cloudletList, vmList);
        } else {
            // 👇 PERUBAHAN: Memanggil Round-Robin
            roundRobinAssignCloudlets(cloudletList, vmList);
        }

        // submit cloudlets
        broker.submitCloudletList(cloudletList);

        // 7. start simulation & stop
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
        Map<Integer, Double> vmExecSums = new HashMap<>(); 

        for (Cloudlet cl : newList) {
            if (cl.getStatus() == Cloudlet.SUCCESS) {
                // ... (pengumpulan metrik) ...
                double exec = cl.getActualCPUTime();
                double start = cl.getExecStartTime();
                double finish = cl.getFinishTime();
                double submitTime = cl.getSubmissionTime(); 
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

        // Imbalance degree:
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

        // resource utilization:
        double resourceUtilization = (vmCount>0 && makespan>0) ? (totalCpuTime / (vmCount * makespan)) : 0.0;

        // energy estimate:
        int totalHosts = NUM_DATACENTER * HOSTS_PER_DATACENTER;
        double totalEnergy = POWER_PER_HOST * totalHosts * makespan; 

        // Build CSV row:
        String scenario = useMOWS ? "MOWS" : "Baseline_RoundRobin"; // Diubah
        String row = String.format(Locale.US,
                "%s,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f",
                scenario, taskCount, run, 
                totalCpuTime, totalWaitTime, avgStart, avgExec, avgFinish, throughput, makespan,
                imbalanceDegree, resourceUtilization, totalEnergy
        );

        return row;
    }

    // --- Metode Implementasi Scheduler ---

    /**
     * Round-Robin: assign each cloudlet sequentially to a VM.
     */
    private static void roundRobinAssignCloudlets(List<Cloudlet> cloudlets, List<Vm> vms) {
        int numVms = vms.size();
        if (numVms == 0) return;

        // Gunakan nextVmIndex global (static) untuk melanjutkan penugasan
        // Jika Anda ingin RR selalu mulai dari VM pertama untuk setiap run, 
        // Anda harus mereset nextVmIndex di awal runMultipleExperiments. 
        // Saat ini, nextVmIndex di-reset di runMultipleExperiments.
        
        for (Cloudlet cl : cloudlets) {
            // 1. Pilih VM berikutnya
            Vm vm = vms.get(nextVmIndex);
            
            // 2. Tetapkan Cloudlet
            cl.setVmId(vm.getId());

            // 3. Update indeks secara melingkar (circular)
            nextVmIndex = (nextVmIndex + 1) % numVms;
        }
    }
    
    // MOWS scheduler (Tidak Berubah)
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
                cl.setVmId(vms.get(0).getId());
            }
        }
    }
    
    // --- Metode Pembuatan Objek CloudSim (Tidak Berubah) ---

    private static Datacenter createDatacenter(String name) throws Exception {
        // ... (implementasi createDatacenter)
        List<Host> hostList = new ArrayList<>();
        for (int i = 0; i < HOSTS_PER_DATACENTER; i++) {
            List<Pe> peList = new ArrayList<>();
            peList.add(new Pe(HOST_PE, new PeProvisionerSimple(HOST_PE_MIPS)));

            Host host = new Host(i, new RamProvisionerSimple(HOST_RAM), new BwProvisionerSimple(HOST_BW), HOST_STORAGE, peList, new VmSchedulerTimeShared(peList));
            hostList.add(host);
        }
        DatacenterCharacteristics characteristics = new DatacenterCharacteristics("x86", "Linux", VMM, hostList, 10.0, HOST_COST, 0.05, 0.1, 0.1);
        return new Datacenter(name, characteristics, new VmAllocationPolicySimple(hostList), new LinkedList<Storage>(), 0);
    }

    private static DatacenterBroker createBroker() throws Exception {
        return new DatacenterBroker("Broker_" + UUID.randomUUID().toString().substring(0,5));
    }

    private static List<Cloudlet> createCloudletList(int brokerId, List<Vm> vmList, int pesNumber, int cloudletAmount) {
        List<Cloudlet> cloudletList = new ArrayList<>();
        UtilizationModelFull utilModel = new UtilizationModelFull();
        List<Integer> lengths = tryReadDatasetLengths(cloudletAmount);

        for (int i = 0; i < cloudletAmount; i++) {
            long length = lengths.get(i);
            Cloudlet cloudlet = new Cloudlet(i, length, pesNumber, CLOUDLET_FILESIZE, CLOUDLET_OUTPUTSIZE, utilModel, utilModel, utilModel);
            cloudlet.setUserId(brokerId);
            cloudletList.add(cloudlet);
        }
        return cloudletList;
    }

    private static List<Integer> tryReadDatasetLengths(int expectedCount) {
    	String filename = "./datasets/randomStratified/RandStratified" + expectedCount + ".txt";
        List<Integer> lens = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null && lens.size() < expectedCount) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    int v = Integer.parseInt(line);
                    lens.add(v);
                } catch (NumberFormatException e) {}
            }
            while (lens.size() < expectedCount) {
                lens.add(5000 + RAND.nextInt(15001)); 
            }
            System.out.println("Loaded dataset file: " + filename + " with " + lens.size() + " lengths.");
            return lens;
        } catch (IOException e) {
            for (int i = 0; i < expectedCount; i++) {
                lens.add(5000 + RAND.nextInt(15001)); 
            }
            System.out.println("Dataset file not found (" + filename + "). Using random lengths as fallback.");
            return lens;
        }
    }
}