package contoh;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.*;
import java.io.*; // Pertahankan import ini, tapi gunakan java.io.File secara eksplisit di main
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

    // Experiment counts (Hanya digunakan di Mode 1)
    static final int[] TASK_COUNTS = {1000, 2000, 3000, 4000, 5000, 6000, 7000, 8000, 9000, 10000};
    
    // Konstanta: Jumlah pengulangan per skenario (10 kali)
    static final int NUM_RUNS = 10; 
    
    // --- PENGATURAN DATASET (Diatur untuk SDSC) ---
    static final int DATASET_MODE = 2; // MODE SDSC AKTIF

    // Jika DATASET_MODE = 1 (Dicadangkan)
    static final String STRUCTURED_BASE_PATH = "./datasets/randomStratified/RandStratified"; 
    static final String STRUCTURED_FILE_EXT = ".txt";

    // Jika DATASET_MODE = 2 (Diaktifkan)
    static final String SDSC_FILE = "./datasets/SDSC/SDSC7395.txt"; 
    
    // Random seed for reproducibility
    static final long SEED = 12345L;
    static final Random RAND = new Random(SEED);
    
    // ROUND-ROBIN: Indeks VM berikutnya yang akan dipilih
    static int nextVmIndex = 0; 

    public static void main(String[] args) {
        try {
            // Header untuk Output Detil (10 percobaan)
            String csvOut = "scenario,taskCount,run,totalCpuTime,totalWaitTime,avgStartTime,avgExecutionTime,avgFinishTime,throughput,makespan,imbalanceDegree,resourceUtilization,totalEnergy\n";

            // Header untuk Tabel Hasil Akhir (Rata-Rata)
            String finalTable = "scenario,taskCount,avgTotalCpuTime,avgTotalWaitTime,avgAvgStartTime,avgAvgExecutionTime,avgAvgFinishTime,avgThroughput,avgMakespan,avgImbalanceDegree,avgResourceUtilization,avgTotalEnergy\n";

            // Tentukan set tugas yang akan diproses
            List<Integer> tasksToProcess = new ArrayList<>();
            List<Integer> initialLengths = null;
            String scenarioMode;

            if (DATASET_MODE == 2) {
                // --- MODE SDSC: Baca data SDSC sekali ---
                initialLengths = readSDSCData();
                if (initialLengths.isEmpty()) {
                     System.err.println("Gagal memuat data SDSC. Eksperimen dibatalkan.");
                     return;
                }
                tasksToProcess.add(initialLengths.size()); 
                scenarioMode = "SDSC";
            } else {
                // --- MODE STRUCTURED (Jika DATASET_MODE diubah ke 1) ---
                for(int t : TASK_COUNTS) tasksToProcess.add(t);
                scenarioMode = STRUCTURED_BASE_PATH.contains("Stratified") ? "Stratified" : "Simple";
            }

            for (int tasks : tasksToProcess) {
                // Gunakan lengths yang sudah dibaca (SDSC) atau null (Structured)
                List<Integer> currentLengths = (DATASET_MODE == 2) ? initialLengths : null;

                // --- MOWS ---
                String mowsResults = runMultipleExperiments(tasks, true, currentLengths);
                csvOut += mowsResults;
                finalTable += calculateAndAppendAverage(tasks, "MOWS_" + scenarioMode, mowsResults);

                // --- Baseline (Round-Robin) ---
                String baselineResults = runMultipleExperiments(tasks, false, currentLengths);
                csvOut += baselineResults;
                finalTable += calculateAndAppendAverage(tasks, "Baseline_RR_" + scenarioMode, baselineResults);

                System.out.printf("Finished %s experiments for tasks=%d (%d runs each)%n", scenarioMode, tasks, NUM_RUNS);
            }

            // Tulis file CSV detil dan ringkasan
            String baseDir = System.getProperty("user.dir") + "/outputs/" + scenarioMode.toLowerCase();
            
            // 👇 PERBAIKAN ERROR: Menggunakan java.io.File secara eksplisit
            new java.io.File(baseDir).mkdirs(); 
            
            String detailFile = baseDir + "/mows_rr_experiment_details.csv";
            try (FileWriter fw = new FileWriter(detailFile)) {
                fw.write(csvOut);
            }
            System.out.println("Detailed CSV saved at: " + detailFile);

            String summaryFile = baseDir + "/mows_rr_experiment_summary.csv";
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

    private static String runMultipleExperiments(int taskCount, boolean useMOWS, List<Integer> preLoadedLengths) throws Exception {
        StringBuilder results = new StringBuilder();
        nextVmIndex = 0; 
        
        for (int run = 1; run <= NUM_RUNS; run++) {
            CloudSim.terminateSimulation(); 
            String runRes = runExperiment(taskCount, useMOWS, run, preLoadedLengths);
            results.append(runRes).append("\n");
        }
        return results.toString();
    }

    private static String calculateAndAppendAverage(int taskCount, String scenario, String runResults) {
        final int METRIC_COUNT = 10; 
        double[] totalMetrics = new double[METRIC_COUNT];
        int actualRuns = 0;

        String[] lines = runResults.split("\n");
        for (String line : lines) {
            if (line.trim().isEmpty()) continue;
            String[] parts = line.split(",");
            if (parts.length < METRIC_COUNT + 3) continue; 

            try {
                for (int i = 0; i < METRIC_COUNT; i++) {
                    totalMetrics[i] += Double.parseDouble(parts[i + 3].trim());
                }
                actualRuns++;
            } catch (NumberFormatException e) {
                System.err.println("Skipping malformed line during average calculation: " + line);
            }
        }

        if (actualRuns == 0) return "";

        StringBuilder avgRowBuilder = new StringBuilder();
        avgRowBuilder.append(scenario).append(",").append(taskCount).append(",");
        
        for (int i = 0; i < METRIC_COUNT; i++) {
            double average = totalMetrics[i] / actualRuns;
            avgRowBuilder.append(String.format(Locale.US, "%.10f", average)); 
            if (i < METRIC_COUNT - 1) {
                avgRowBuilder.append(",");
            }
        }
        
        return avgRowBuilder.toString() + "\n";
    }

    // --- Metode Inti CloudSim ---

    private static String runExperiment(int taskCount, boolean useMOWS, int run, List<Integer> preLoadedLengths) throws Exception {
        int numUser = 1;
        Calendar calendar = Calendar.getInstance();
        boolean traceFlag = false;
        CloudSim.init(numUser, calendar, traceFlag);

        List<Datacenter> datacenters = new ArrayList<>();
        for (int d = 0; d < NUM_DATACENTER; d++) {
            datacenters.add(createDatacenter("Datacenter_" + d));
        }

        DatacenterBroker broker = createBroker();
        int brokerId = broker.getId();

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

        List<Integer> lengths = getTaskLengths(taskCount, preLoadedLengths); 
        List<Cloudlet> cloudletList = createCloudletList(brokerId, vmList, 1, lengths);

        if (useMOWS) {
            scheduleWithMOWS(cloudletList, vmList);
        } else {
            roundRobinAssignCloudlets(cloudletList, vmList);
        }

        broker.submitCloudletList(cloudletList);

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

        double resourceUtilization = (vmCount>0 && makespan>0) ? (totalCpuTime / (vmCount * makespan)) : 0.0;

        int totalHosts = NUM_DATACENTER * HOSTS_PER_DATACENTER;
        double totalEnergy = POWER_PER_HOST * totalHosts * makespan; 

        String scenario = useMOWS ? "MOWS" : "Baseline_RoundRobin"; 
        String row = String.format(Locale.US,
                "%s,%d,%d,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f,%.10f",
                scenario, taskCount, run, 
                totalCpuTime, totalWaitTime, avgStart, avgExec, avgFinish, throughput, makespan,
                imbalanceDegree, resourceUtilization, totalEnergy
        );

        return row;
    }

    // --- Metode Implementasi Scheduler ---

    private static void roundRobinAssignCloudlets(List<Cloudlet> cloudlets, List<Vm> vms) {
        int numVms = vms.size();
        if (numVms == 0) return;
        
        for (Cloudlet cl : cloudlets) {
            Vm vm = vms.get(nextVmIndex);
            cl.setVmId(vm.getId());
            nextVmIndex = (nextVmIndex + 1) % numVms;
        }
    }
    
    private static void scheduleWithMOWS(List<Cloudlet> cloudlets, List<Vm> vms) {
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

            double taskSecDemand = RAND.nextDouble(); 

            for (Vm vm : vms) {
                double vmMipsNorm = Math.min(1.0, (double)vm.getMips() / maxVmMips);
                double vmBwNorm = Math.min(1.0, (double)vm.getBw() / maxVmBw);
                double vmSecurity = RAND.nextDouble(); 

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
    
    // --- Metode Implementasi Pembacaan Dataset Baru ---

    private static List<Integer> getTaskLengths(int taskCount, List<Integer> preLoadedLengths) {
        if (DATASET_MODE == 2) {
            return preLoadedLengths;
        } else {
            return readStructuredData(taskCount);
        }
    }
    
    private static List<Integer> readStructuredData(int expectedCount) {
        String filename = STRUCTURED_BASE_PATH + expectedCount + STRUCTURED_FILE_EXT;
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

    /**
     * Membaca file SDSC. Disederhanakan untuk mengasumsikan setiap baris berisi
     * panjang tugas tunggal (MI), karena format file yang diberikan.
     */
    private static List<Integer> readSDSCData() {
        String filename = SDSC_FILE;
        List<Integer> lens = new ArrayList<>();
        int successfulTasks = 0;
        
        try (BufferedReader br = new BufferedReader(new FileReader(filename))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                // Lewati baris komentar/header SDSC (biasanya dimulai dengan ';')
                if (line.isEmpty() || line.startsWith(";")) continue; 
                
                try {
                    // LANGKAH PENTING: Coba parse seluruh baris sebagai nilai panjang tugas.
                    // Jika angkanya besar, gunakan Long.
                    long length = (long)Double.parseDouble(line); // Pakai Double.parseDouble dulu untuk menangani nilai 0.96, dll.
                    
                    if (length > 0) {
                        // Batasi agar tidak crash jika panjang tugas melebihi batas Integer
                        if (length > Integer.MAX_VALUE) { 
                           length = Integer.MAX_VALUE;
                        }
                        lens.add((int) length); 
                        successfulTasks++;
                    }
                } catch (NumberFormatException e) {
                    // Abaikan baris yang bukan angka
                    // System.out.println("DEBUG: Skipping line: " + line + " (Not a number)");
                }
            }
            System.out.println("Loaded SDSC dataset file: " + filename + " with " + successfulTasks + " tasks.");
            return lens;
        } catch (IOException e) {
            System.out.println("SDSC Dataset file not found (" + filename + "). Returning empty list.");
            return lens;
        }
    }
    
    private static List<Cloudlet> createCloudletList(int brokerId, List<Vm> vmList, int pesNumber, List<Integer> lengths) {
        List<Cloudlet> cloudletList = new ArrayList<>();
        UtilizationModelFull utilModel = new UtilizationModelFull();
        int cloudletAmount = lengths.size();

        for (int i = 0; i < cloudletAmount; i++) {
            long length = lengths.get(i);
            Cloudlet cloudlet = new Cloudlet(i, length, pesNumber, CLOUDLET_FILESIZE, CLOUDLET_OUTPUTSIZE, utilModel, utilModel, utilModel);
            cloudlet.setUserId(brokerId);
            cloudletList.add(cloudlet);
        }
        return cloudletList;
    }
    
    // --- Metode Pembuatan Objek CloudSim ---

    private static Datacenter createDatacenter(String name) throws Exception {
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
}