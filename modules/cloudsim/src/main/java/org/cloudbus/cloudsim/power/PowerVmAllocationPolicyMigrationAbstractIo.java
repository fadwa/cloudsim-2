/*
 * Title:        CloudSim Toolkit
 * Description:  CloudSim (Cloud Simulation) Toolkit for Modeling and Simulation of Clouds
 * Licence:      GPL - http://www.gnu.org/copyleft/gpl.html
 *
 * Copyright (c) 2009-2012, The University of Melbourne, Australia
 */

package org.cloudbus.cloudsim.power;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cloudbus.cloudsim.Host;
import org.cloudbus.cloudsim.HostDynamicWorkload;
import org.cloudbus.cloudsim.Log;
import org.cloudbus.cloudsim.Vm;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.power.lists.PowerVmIoList;
import org.cloudbus.cloudsim.power.lists.PowerVmList;
import org.cloudbus.cloudsim.util.ExecutionTimeMeasurer;

/**
 * The class of an abstract power-aware VM allocation policy that dynamically optimizes the VM
 * allocation using migration.
 * 
 * If you are using any algorithms, policies or workload included in the power package, please cite
 * the following paper:
 * 
 * Anton Beloglazov, and Rajkumar Buyya, "Optimal Online Deterministic Algorithms and Adaptive
 * Heuristics for Energy and Performance Efficient Dynamic Consolidation of Virtual Machines in
 * Cloud Data Centers", Concurrency and Computation: Practice and Experience (CCPE), Volume 24,
 * Issue 13, Pages: 1397-1420, John Wiley & Sons, Ltd, New York, USA, 2012
 * 
 * @author Anton Beloglazov
 * @since CloudSim Toolkit 3.0
 */
public abstract class PowerVmAllocationPolicyMigrationAbstractIo extends PowerVmAllocationPolicyMigrationAbstract {
	
	/**The significance of the mips utilization in the allocation process*/
	protected double weightMipsUtil;

	/**The significance of the iops utilization in the allocation process*/
	protected double weightIopsUtil;
	
	/** The vm selection policy. */
	private PowerVmSelectionPolicyIo vmSelectionPolicyIo;

	/** The saved allocation. */
	private final List<Map<String, Object>> savedAllocation = new ArrayList<Map<String, Object>>();

	/** The utilization history. */
	private final Map<Integer, List<Double>> utilizationHistoryIo = new HashMap<Integer, List<Double>>();

	/** The metric history. */
	private final Map<Integer, List<Double>> metricHistoryIo = new HashMap<Integer, List<Double>>();

	/** The time history. */
	private final Map<Integer, List<Double>> timeHistoryIo = new HashMap<Integer, List<Double>>();

	/** The execution time history vm selection. */
	private final List<Double> executionTimeHistoryVmSelection = new LinkedList<Double>();

	/** The execution time history host selection. */
	private final List<Double> executionTimeHistoryHostSelection = new LinkedList<Double>();
	
	/** The execution time history host selection for IO. */
	private final List<Double> executionTimeHistoryHostSelectionIo = new LinkedList<Double>();

	/** The execution time history vm reallocation. */
	private final List<Double> executionTimeHistoryVmReallocation = new LinkedList<Double>();

	/** The execution time history total. */
	private final List<Double> executionTimeHistoryTotal = new LinkedList<Double>();

	/**
	 * Instantiates a new power vm allocation policy migration abstract.
	 * 
	 * @param hostList the host list
	 * @param vmSelectionPolicy the vm selection policy
	 */
	public PowerVmAllocationPolicyMigrationAbstractIo(
			List<? extends Host> hostList,
			PowerVmSelectionPolicy vmSelectionPolicy, PowerVmSelectionPolicyIo vmSelectionPolicyIo,
			double weightMipsUtil, double weightIopsUtil) {
		super(hostList, vmSelectionPolicy);
		if (weightIopsUtil + weightMipsUtil != 1.0){
			System.err.printf("WeightMipsUtil(%.3f) and WeightIopsUtil(%.3f) should sum to 1.\nExiting...",
					weightMipsUtil, weightIopsUtil);
			System.exit(0);
		}
		vmSelectionPolicyIo.setWeightIopsUtil(weightIopsUtil);
		vmSelectionPolicyIo.setWeightMipsUtil(weightMipsUtil);
		this.setVmSelectionPolicyIo(vmSelectionPolicyIo);
		setWeightIopsUtil(weightIopsUtil);
		setWeightMipsUtil(weightMipsUtil);
	}

	/**
	 * Optimize allocation of the VMs according to current utilization.
	 * 
	 * @param vmList the vm list
	 * 
	 * @return the array list< hash map< string, object>>
	 */
	@Override
	public List<Map<String, Object>> optimizeAllocation(List<? extends Vm> vmList) {
		ExecutionTimeMeasurer.start("optimizeAllocationTotal");
		
		ExecutionTimeMeasurer.start("optimizeAllocationHostSelection");
		List<PowerHostUtilizationHistoryIo> overUtilizedHostsCpu = getOverUtilizedHostsCpu();
		getExecutionTimeHistoryHostSelection().add(
				ExecutionTimeMeasurer.end("optimizeAllocationHostSelection"));

		printOverUtilizedHosts(overUtilizedHostsCpu);

		ExecutionTimeMeasurer.start("optimizeAllocationHostSelectionIo");
		List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo = getOverUtilizedHostsIo();
		getExecutionTimeHistoryHostSelectionIo().add(
				ExecutionTimeMeasurer.end("optimizeAllocationHostSelectionIo"));

		printOverUtilizedHostsIo(overUtilizedHostsIo);

		saveAllocation();

		ExecutionTimeMeasurer.start("optimizeAllocationVmSelectionIo");
		List<List<? extends Vm>> vmsToMigrate = getVmsToMigrateFromHosts(overUtilizedHostsCpu, overUtilizedHostsIo);
		//TODO: Refactor to remove coupling with getVmsToMigrateFromHosts. We shouldn't have to know the indice of each vmsToMigrate list.
		List<? extends Vm> vmsToMigrateCpu = vmsToMigrate.get(0);
		List<? extends Vm> vmsToMigrateIo = vmsToMigrate.get(1);
		
		getExecutionTimeHistoryVmSelection().add(ExecutionTimeMeasurer.end("optimizeAllocationVmSelectionIo"));

		Log.printLine("Reallocation of VMs from the over-utilized hosts:");
		ExecutionTimeMeasurer.start("optimizeAllocationVmReallocation");
		List<Map<String, Object>> migrationMap = getNewVmPlacement(
				vmsToMigrateCpu,
				vmsToMigrateIo,
				new HashSet<Host>(overUtilizedHostsCpu),
				new HashSet<Host>(overUtilizedHostsIo)
				);
		getExecutionTimeHistoryVmReallocation().add(
				ExecutionTimeMeasurer.end("optimizeAllocationVmReallocation"));
		Log.printEmptyLine();

		migrationMap.addAll(getMigrationMapFromUnderUtilizedHosts(overUtilizedHostsCpu, overUtilizedHostsIo));

		restoreAllocation();

		getExecutionTimeHistoryTotal().add(ExecutionTimeMeasurer.end("optimizeAllocationTotal"));

		return migrationMap;
	}

	
	/**
	 * Gets the migration map from under utilized hosts (Iops && Mips).
	 * 
	 * @param overUtilizedHostsCpu the over utilized hosts (Mips)
	 * @param overUtilizedHostsIo the over utilized hosts (Iops)
	 * @return the migration map from under utilized hosts
	 */
	protected List<Map<String, Object>> getMigrationMapFromUnderUtilizedHosts(
			List<PowerHostUtilizationHistoryIo> overUtilizedHostsCpu,
			List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo) {
				
		List<PowerHostUtilizationHistoryIo> overUtilizedHosts = new LinkedList<PowerHostUtilizationHistoryIo>(overUtilizedHostsCpu);
		overUtilizedHosts.addAll(overUtilizedHostsIo);
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();
		List<PowerHost> switchedOffHosts = getSwitchedOffHosts();

		// over-utilized hosts + hosts that are selected to migrate VMs to from over-utilized hosts
		Set<PowerHost> excludedHostsForFindingUnderUtilizedHost = new HashSet<PowerHost>();
		excludedHostsForFindingUnderUtilizedHost.addAll(overUtilizedHosts);
		excludedHostsForFindingUnderUtilizedHost.addAll(switchedOffHosts);
		excludedHostsForFindingUnderUtilizedHost.addAll(extractHostListFromMigrationMap(migrationMap));

		// over-utilized + under-utilized hosts
		Set<PowerHost> excludedHostsForFindingNewVmPlacement = new HashSet<PowerHost>();
		excludedHostsForFindingNewVmPlacement.addAll(overUtilizedHosts);
		excludedHostsForFindingNewVmPlacement.addAll(switchedOffHosts);

		int numberOfHosts = getHostList().size();

		while (true) {
			if (numberOfHosts == excludedHostsForFindingUnderUtilizedHost.size()) {
				break;
			}

			PowerHost underUtilizedHost = getUnderUtilizedHost(excludedHostsForFindingUnderUtilizedHost);
			if (underUtilizedHost == null) {
				break;
			}

			Log.printLine("Under-utilized host: host #" + underUtilizedHost.getId() + "\n");

			excludedHostsForFindingUnderUtilizedHost.add(underUtilizedHost);
			excludedHostsForFindingNewVmPlacement.add(underUtilizedHost);

			List<? extends Vm> vmsToMigrateFromUnderUtilizedHost = getVmsToMigrateFromUnderUtilizedHost(underUtilizedHost);
			if (vmsToMigrateFromUnderUtilizedHost.isEmpty()) {
				continue;
			}

			Log.print("Reallocation of VMs from the under-utilized host: ");
			if (!Log.isDisabled()) {
				for (Vm vm : vmsToMigrateFromUnderUtilizedHost) {
					Log.print(vm.getId() + " ");
				}
			}
			Log.printEmptyLine();

			List<Map<String, Object>> newVmPlacement = getNewVmPlacementFromUnderUtilizedHost(
					vmsToMigrateFromUnderUtilizedHost,
					excludedHostsForFindingNewVmPlacement);

			excludedHostsForFindingUnderUtilizedHost.addAll(extractHostListFromMigrationMap(newVmPlacement));

			migrationMap.addAll(newVmPlacement);
			Log.printEmptyLine();
		}

		return migrationMap;
	}

	/**
	 * Prints the over utilized hosts.
	 * 
	 * @param overUtilizedHosts the over utilized hosts
	 */
	protected void printOverUtilizedHostsIo(List<PowerHostUtilizationHistoryIo> overUtilizedHosts) {
		if (!Log.isDisabled()) {
			Log.printLine("Over-utilized hosts Io:");
			for (PowerHostUtilizationHistoryIo host : overUtilizedHosts) {
				Log.printLine("Host #" + host.getId());
			}
			Log.printEmptyLine();
		}
	}

	/**
	 * Find host for vm.
	 * 
	 * @param vm the vm
	 * @param excludedHosts the excluded hosts
	 * @return the power host
	 */
	public PowerHost findHostForVm(Vm vm, Set<? extends Host> excludedHosts) {
		double minPower = Double.MAX_VALUE;
		PowerHost allocatedHost = null;

		for (PowerHost host : this.<PowerHost> getHostList()) {
			if (excludedHosts.contains(host)) {
				continue;
			}
			if (host.isSuitableForVm(vm)) {
				//TODO: should we look also if utilizedIops = 0?
				if (getUtilizationOfCpuMips(host) != 0 && getUtilizationOfIops(host) !=0 
						&& isHostOverUtilizedAfterAllocation(host, vm)) {
					continue;
				}

				try {
					double powerAfterAllocation = getPowerAfterAllocation(host, vm);
					if (powerAfterAllocation != -1) {
						double powerDiff = powerAfterAllocation - host.getPower();
						if (powerDiff < minPower) {
							minPower = powerDiff;
							allocatedHost = host;
						}
					}
				} catch (Exception e) {
				}
			}
		}
		return allocatedHost;
	}

	/**
	 * Checks if is host over utilized after allocation.
	 * 
	 * @param host the host
	 * @param vm the vm
	 * @return true, if is host over utilized after allocation
	 */
	protected boolean isHostOverUtilizedAfterAllocation(PowerHost host, Vm vm) {
		boolean isHostOverUtilizedAfterAllocation = true;
		if (host.vmCreate(vm)) {
			isHostOverUtilizedAfterAllocation = isHostOverUtilized(host) || isHostOverUtilizedIo(host);
			host.vmDestroy(vm);
		}
		return isHostOverUtilizedAfterAllocation;
	}

	/**
	 * Find host for vm.
	 * 
	 * @param vm the vm
	 * @return the power host
	 */
	@Override
	public PowerHost findHostForVm(Vm vm) {
		Set<Host> excludedHosts = new HashSet<Host>();
		if (vm.getHost() != null) {
			excludedHosts.add(vm.getHost());
		}
		return findHostForVm(vm, excludedHosts);
	}

	/**
	 * Extract host list from migration map.
	 * 
	 * @param migrationMap the migration map
	 * @return the list
	 */
	protected List<PowerHost> extractHostListFromMigrationMap(List<Map<String, Object>> migrationMap) {
		List<PowerHost> hosts = new LinkedList<PowerHost>();
		for (Map<String, Object> map : migrationMap) {
			hosts.add((PowerHost) map.get("host"));
		}
		return hosts;
	}

	/**
	 * Gets the new vm placement.
	 * 
	 * @param vmsToMigrateCpu the vms to migrate retreived from hosts with overutilized cpu
	 * @param vmsToMigrateIo the vms to migrate retreived from hosts with overutilized io
	 * @param excludedHostsCpu the excluded hosts (overutilized Cpu)
	 * @param excludedHostsIo the excluded hosts (overutilized Io)
	 * @return the new vm placement
	 */
	protected List<Map<String, Object>> getNewVmPlacement(
			List<? extends Vm> vmsToMigrateCpu,
			List<? extends Vm> vmsToMigrateIo,		
			Set<? extends Host> excludedHostsCpu,
			Set<? extends Host> excludedHostsIo) {
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();
		//TODO: check if it's better to search if some excludedHosts are fit recipients for a vm leaving a host overutilizing a different resource.
		Set<Host> excludedHosts = new HashSet<Host>(excludedHostsCpu);
		excludedHosts.addAll(excludedHostsIo);
		
		PowerVmIoList.sortByCpuUtilization(vmsToMigrateCpu);
		PowerVmIoList.sortByIoUtilization(vmsToMigrateIo);
		
		if (this.weightMipsUtil > this.weightIopsUtil) {
			migrationMap.addAll( getNewVmPlacement(vmsToMigrateCpu, excludedHosts) );
			migrationMap.addAll( getNewVmPlacement(vmsToMigrateIo, excludedHosts) );
		} else {
			migrationMap.addAll( getNewVmPlacement(vmsToMigrateIo, excludedHosts) );
			migrationMap.addAll( getNewVmPlacement(vmsToMigrateCpu, excludedHosts) );
		}

		return migrationMap;
	}
		
	/**
	 * Gets the new vm placement based on  resource.
	 * 
	 * @param vmsToMigrate the vms to migrate should be already sorted by the preferred resource utilization
	 * @param excludedHosts the excluded hosts
	 * @return the new vm placement
	 */		
	protected List<Map<String, Object>> getNewVmPlacement(
			List<? extends Vm> vmsToMigrate,
			Set<?extends Host> excludedHosts) {
		
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();

		for (Vm vm : vmsToMigrate) {
			PowerHost allocatedHost = findHostForVm(vm, excludedHosts);
			if (allocatedHost != null) {
				allocatedHost.vmCreate(vm);
				Log.printLine("VM #" + vm.getId() + " allocated to host #" + allocatedHost.getId());

				Map<String, Object> migrate = new HashMap<String, Object>();
				migrate.put("vm", vm);
				migrate.put("host", allocatedHost);
				migrationMap.add(migrate);
			}
		}
		return migrationMap;
	}

	/**
	 * Gets the new vm placement from under utilized host.
	 * 
	 * @param vmsToMigrate the vms to migrate
	 * @param excludedHosts the excluded hosts
	 * @return the new vm placement from under utilized host
	 */
	protected List<Map<String, Object>> getNewVmPlacementFromUnderUtilizedHost(
			List<? extends Vm> vmsToMigrate,
			Set<? extends Host> excludedHosts) {
		List<Map<String, Object>> migrationMap = new LinkedList<Map<String, Object>>();
		if (this.weightMipsUtil > this.weightIopsUtil){
			PowerVmIoList.sortByCpuUtilization(vmsToMigrate);
		} else {
			PowerVmIoList.sortByIoUtilization(vmsToMigrate);	
		}
		for (Vm vm : vmsToMigrate) {
			PowerHost allocatedHost = findHostForVm(vm, excludedHosts);
			if (allocatedHost != null) {
				allocatedHost.vmCreate(vm);
				Log.printLine("VM #" + vm.getId() + " allocated to host #" + allocatedHost.getId());

				Map<String, Object> migrate = new HashMap<String, Object>();
				migrate.put("vm", vm);
				migrate.put("host", allocatedHost);
				migrationMap.add(migrate);
			} else {
				Log.printLine("Not all VMs can be reallocated from the host, reallocation cancelled");
				for (Map<String, Object> map : migrationMap) {
					((Host) map.get("host")).vmDestroy((Vm) map.get("vm"));
				}
				migrationMap.clear();
				break;
			}
		}
		return migrationMap;
	}

	/**
	 * Gets the vms to migrate from hosts.
	 * 
	 * @param overUtilizedHostsIo the over utilized hosts (Iops)
	 * @return the vms to migrate from hosts
	 */
	protected
			List<List<? extends Vm>>
			getVmsToMigrateFromHosts(List<PowerHostUtilizationHistoryIo> overUtilizedHostsCpu, List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo) {
		List<PowerHostUtilizationHistoryIo> overUtilizedHostsCommon = findCommonOverUtilizedHosts(overUtilizedHostsCpu, overUtilizedHostsIo);
		//removing common hosts from the other lists
		overUtilizedHostsCpu.removeAll(overUtilizedHostsCommon);
		overUtilizedHostsIo.removeAll(overUtilizedHostsCommon);

		//TODO: refactor tight coupling with getVmsToMigrateFromHostsCommon
		List<List<Vm>> vmsToMigrateCommon = getVmsToMigrateFromHostsCommon(overUtilizedHostsCommon);
		List<Vm> vmsToMigrateCpu = new LinkedList<Vm>(vmsToMigrateCommon.get(0));
		vmsToMigrateCpu.addAll(getVmsToMigrateFromHostsCpu(overUtilizedHostsCpu));
 		List<Vm> vmsToMigrateIo = new LinkedList<Vm>(vmsToMigrateCommon.get(1));
		vmsToMigrateIo.addAll(getVmsToMigrateFromHostsIo(overUtilizedHostsIo));
		
		List<List<? extends Vm>> vmsToMigrate = new LinkedList<List<? extends Vm>>();
		vmsToMigrate.add(0,vmsToMigrateCpu);
		vmsToMigrate.add(1,vmsToMigrateIo);
		//TODO: Should we return one merged list or an array of the two list
		return vmsToMigrate;
	}
	
	List<Vm> getVmsToMigrateFromHostsIo(List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo) {

		List<Vm> vmsToMigrateIo = new LinkedList<Vm>();
		
		for (PowerHostUtilizationHistoryIo host : overUtilizedHostsIo) {
			while (true) {
				Vm vm = getVmSelectionPolicyIo().getVmToMigrate(host);
				if (vm == null) {
					break;
				}
				vmsToMigrateIo.add(vm);
				host.vmDestroy(vm);
				if (!isHostOverUtilizedIo(host)) {
					break;
				}
			}
		}
		
		return vmsToMigrateIo;
	}
	
	List<Vm> getVmsToMigrateFromHostsCpu(List<PowerHostUtilizationHistoryIo> overUtilizedHostsCpu) {

		List<Vm> vmsToMigrateCpu = new LinkedList<Vm>();
		
		for (PowerHostUtilizationHistory host : overUtilizedHostsCpu) {
			while (true) {
				Vm vm = getVmSelectionPolicy().getVmToMigrate(host);
				if (vm == null) {
					break;
				}
				vmsToMigrateCpu.add(vm);
				host.vmDestroy(vm);
				if (!isHostOverUtilized(host)) {
					break;
				}
			}
		}
		
		return vmsToMigrateCpu;
	}
	
	/**
	 * 	 * 
	 * @param overUtilizedHostsCommon
	 * @return returns a list of list of vms. The first list is the vms removed from hosts because they have overutilized cpu. The second is from hosts
	 * because they have overutilized io.
	 */
	List<List<Vm>> getVmsToMigrateFromHostsCommon(List<PowerHostUtilizationHistoryIo> overUtilizedHostsCommon) {

		List<Vm> vmsToMigrateIo = new LinkedList<Vm>();
		List<Vm> vmsToMigrateCpu = new LinkedList<Vm>();
						
		if (weightMipsUtil > weightIopsUtil){
			vmsToMigrateCpu.addAll(getVmsToMigrateFromHostsCpu(overUtilizedHostsCommon));
			List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo = new LinkedList<PowerHostUtilizationHistoryIo>(overUtilizedHostsCommon);
			//Remove hosts that are not overutilized in IO anymore.
			for (PowerHostUtilizationHistoryIo host: overUtilizedHostsCommon) {
				if (!isHostOverUtilizedIo(host)){
					overUtilizedHostsIo.remove(host);
				}
			}
			vmsToMigrateIo.addAll(getVmsToMigrateFromHostsIo(overUtilizedHostsIo));
		} else {
			vmsToMigrateIo.addAll(getVmsToMigrateFromHostsIo(overUtilizedHostsCommon));
			List<PowerHostUtilizationHistoryIo> overUtilizedHostsCpu = new LinkedList<PowerHostUtilizationHistoryIo>(overUtilizedHostsCommon);
			//Remove hosts that are not overutilized in CPU anymore.
			for (PowerHostUtilizationHistoryIo host: overUtilizedHostsCommon) {
				if (!isHostOverUtilized(host)){
					overUtilizedHostsCpu.remove(host);
				}
			}
			vmsToMigrateCpu.addAll(getVmsToMigrateFromHostsCpu(overUtilizedHostsCpu));
			
		}
		
		List<List<Vm>> vmsToMigrateCommon = new LinkedList<List<Vm>>();
		vmsToMigrateCommon.add(0, vmsToMigrateCpu);
		vmsToMigrateCommon.add(1, vmsToMigrateIo);
		
		return vmsToMigrateCommon;
	}
	
	protected List<PowerHostUtilizationHistoryIo> findCommonOverUtilizedHosts(
			List<PowerHostUtilizationHistoryIo> overUtilizedHostsCpu,
			List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo) {
		List<PowerHostUtilizationHistoryIo> overUtilizedHostsCommon = new LinkedList<PowerHostUtilizationHistoryIo>();
		//Find the hosts that are overutilized both in cpu and io.
		for (PowerHostUtilizationHistoryIo host : overUtilizedHostsIo) {
			if (overUtilizedHostsCpu.contains(host)){
				overUtilizedHostsCommon.add(host);
			}
		}
		return overUtilizedHostsCommon;
	}
	
/*	
	*//**
	 * Gets the vms to migrate from hosts.
	 * 
	 * @param commonOverUtilizedHosts the over utilized hosts (Iops && Mips)
	 * @return the vms to migrate from hosts
	 *//*
	protected
			List<? extends Vm>
			getVmsToMigrateFromHostsCommon(List<PowerHostUtilizationHistoryIo> overUtilizedHostsCommon,
					double weightMipsUtil, double weightIopsUtil) {
		List<Vm> vmsToMigrate = new LinkedList<Vm>();
		If mips utilization is more significant that iops util then we first choose to migrate vms based
		 *  on their mips utilization and after that (if it's still needed) choose to migrate based on their
		 *  iops utilization. If iops utilization is more significant we do it the ther way.
		 
		if (weightMipsUtil > weightIopsUtil){
			for (PowerHostUtilizationHistory host : overUtilizedHostsCommon) {
				while (true) {
					Vm vm = getVmSelectionPolicy().getVmToMigrate(host);
					if (vm == null) {
						break;
					}
					vmsToMigrate.add(vm);
					host.vmDestroy(vm);
					if (!isHostOverUtilized(host)) {
						break;
					}
				}
				//checking to see if vm migrations based on iops are necessary;
				if (!isHostOverUtilizedIo(host)){
					continue;
				}
				while (true) {
					Vm vm = getVmSelectionPolicyIo().getVmToMigrate(host);
					if (vm == null) {
						break;
					}
					vmsToMigrate.add(vm);
					host.vmDestroy(vm);
					if (!isHostOverUtilizedIo(host)) {
						break;
					}
				}
			}
		} else {
			for (PowerHostUtilizationHistory host : overUtilizedHostsCommon) {
				while (true) {
					Vm vm = getVmSelectionPolicyIo().getVmToMigrate(host);
					if (vm == null) {
						break;
					}
					vmsToMigrate.add(vm);
					host.vmDestroy(vm);
					if (!isHostOverUtilizedIo(host)) {
						break;
					}
				}
				//checking to see if vm migrations based on mips are necessary;
				if (!isHostOverUtilized(host)){
					continue;
				}
				while (true) {
					Vm vm = getVmSelectionPolicy().getVmToMigrate(host);
					if (vm == null) {
						break;
					}
					vmsToMigrate.add(vm);
					host.vmDestroy(vm);
					if (!isHostOverUtilized(host)) {
						break;
					}
				}
			}
		}
		return vmsToMigrate;
	}
*/
	/**
	 * Gets the vms to migrate from under utilized host.
	 * 
	 * @param host the host
	 * @return the vms to migrate from under utilized host
	 */
	protected List<? extends Vm> getVmsToMigrateFromUnderUtilizedHost(PowerHost host) {
		List<Vm> vmsToMigrate = new LinkedList<Vm>();
		for (Vm vm : host.getVmList()) {
			if (!vm.isInMigration()) {
				vmsToMigrate.add(vm);
			}
		}
		return vmsToMigrate;
	}

	/**
	 * Gets the over utilized hosts.
	 * 
	 * @return the over utilized hosts
	 */
	protected List<PowerHostUtilizationHistoryIo> getOverUtilizedHostsCpu() {
		List<PowerHostUtilizationHistoryIo> overUtilizedHosts = new LinkedList<PowerHostUtilizationHistoryIo>();
		for (PowerHostUtilizationHistoryIo host : this.<PowerHostUtilizationHistoryIo> getHostList()) {
			if (isHostOverUtilized(host)) {
				overUtilizedHosts.add(host);
			}
		}
		return overUtilizedHosts;
	}

	
	/**
	 * Gets the over utilized hosts.
	 * 
	 * @return the over utilized hosts
	 */
	protected List<PowerHostUtilizationHistoryIo> getOverUtilizedHostsIo() {
		List<PowerHostUtilizationHistoryIo> overUtilizedHostsIo = new LinkedList<PowerHostUtilizationHistoryIo>();
		for (PowerHostUtilizationHistoryIo host : this.<PowerHostUtilizationHistoryIo> getHostList()) {
			if (isHostOverUtilizedIo(host)) {
				overUtilizedHostsIo.add(host);
			}
		}
		return overUtilizedHostsIo;
	}
	
	/**
	 * Checks if is host over utilized.
	 * 
	 * @param host the host
	 * @return true, if is host over utilized
	 */
	protected abstract boolean isHostOverUtilizedIo(PowerHost host);


	/**
	 * Gets the switched off host.
	 * 
	 * @return the switched off host
	 */
	protected List<PowerHost> getSwitchedOffHosts() {
		List<PowerHost> switchedOffHosts = new LinkedList<PowerHost>();
		for (PowerHost host : this.<PowerHost> getHostList()) {
			//TODO: Should we switch-off a host that has no cpu util but has io util?
			if (host.getUtilizationOfCpu() == 0 && host.getUtilizationOfIo() == 0) {
				switchedOffHosts.add(host);
			}
		}
		return switchedOffHosts;
	}

	/**
	 * Gets the under utilized host.
	 * 
	 * @param excludedHosts the excluded hosts
	 * @return the under utilized host
	 */
	protected PowerHost getUnderUtilizedHost(Set<? extends Host> excludedHosts) {
		return (this.weightMipsUtil > this.weightMipsUtil) ? getUnderUtilizedHostCpu(excludedHosts) : getUnderUtilizedHostIo(excludedHosts) ;
	}
	
	
	/**
	 * Gets the under utilized host. (io)
	 * 
	 * @param excludedHosts the excluded hosts
	 * @return the under utilized host
	 */
	protected PowerHost getUnderUtilizedHostIo(Set<? extends Host> excludedHosts) {
		double minUtilization = 1;
		PowerHost underUtilizedHost = null;
		for (PowerHost host : this.<PowerHost> getHostList()) {
			if (excludedHosts.contains(host)) {
				continue;
			}
			double utilization = host.getUtilizationOfIo();
			if (utilization > 0 && utilization < minUtilization
					&& !areAllVmsMigratingOutOrAnyVmMigratingIn(host)) {
				minUtilization = utilization;
				underUtilizedHost = host;
			}
		}
		return underUtilizedHost;
	}
	
	
	/**
	 * Gets the under utilized host. (cpu)
	 * 
	 * @param excludedHosts the excluded hosts
	 * @return the under utilized host
	 */
	protected PowerHost getUnderUtilizedHostCpu(Set<? extends Host> excludedHosts) {
		double minUtilization = 1;
		PowerHost underUtilizedHost = null;
		for (PowerHost host : this.<PowerHost> getHostList()) {
			if (excludedHosts.contains(host)) {
				continue;
			}
			double utilization = host.getUtilizationOfCpu();
			if (utilization > 0 && utilization < minUtilization
					&& !areAllVmsMigratingOutOrAnyVmMigratingIn(host)) {
				minUtilization = utilization;
				underUtilizedHost = host;
			}
		}
		return underUtilizedHost;
	}

	/**
	 * Checks whether all vms are in migration.
	 * 
	 * @param host the host
	 * @return true, if successful
	 */
	protected boolean areAllVmsMigratingOutOrAnyVmMigratingIn(PowerHost host) {
		for (PowerVm vm : host.<PowerVm> getVmList()) {
			if (!vm.isInMigration()) {
				return false;
			}
			if (host.getVmsMigratingIn().contains(vm)) {
				return true;
			}
		}
		return true;
	}

	/**
	 * Checks if is host over utilized.
	 * 
	 * @param host the host
	 * @return true, if is host over utilized
	 */
	protected abstract boolean isHostOverUtilized(PowerHost host);

	/**
	 * Adds the history value.
	 * 
	 * @param host the host
	 * @param metric the metric
	 */
	protected void addHistoryEntryIo(HostDynamicWorkload host, double metric) {
		int hostId = host.getId();
		if (!getTimeHistoryIo().containsKey(hostId)) {
			getTimeHistoryIo().put(hostId, new LinkedList<Double>());
		}
		if (!getUtilizationHistoryIo().containsKey(hostId)) {
			getUtilizationHistoryIo().put(hostId, new LinkedList<Double>());
		}
		if (!getMetricHistoryIo().containsKey(hostId)) {
			getMetricHistoryIo().put(hostId, new LinkedList<Double>());
		}
		if (!getTimeHistoryIo().get(hostId).contains(CloudSim.clock())) {
			getTimeHistoryIo().get(hostId).add(CloudSim.clock());
			getUtilizationHistoryIo().get(hostId).add(host.getUtilizationOfCpu());
			getMetricHistoryIo().get(hostId).add(metric);
		}
	}

	/**
	 * Save allocation.
	 */
	protected void saveAllocation() {
		getSavedAllocation().clear();
		for (Host host : getHostList()) {
			for (Vm vm : host.getVmList()) {
				if (host.getVmsMigratingIn().contains(vm)) {
					continue;
				}
				Map<String, Object> map = new HashMap<String, Object>();
				map.put("host", host);
				map.put("vm", vm);
				getSavedAllocation().add(map);
			}
		}
	}

	/**
	 * Restore allocation.
	 */
	protected void restoreAllocation() {
		for (Host host : getHostList()) {
			host.vmDestroyAll();
			host.reallocateMigratingInVms();
		}
		for (Map<String, Object> map : getSavedAllocation()) {
			Vm vm = (Vm) map.get("vm");
			PowerHost host = (PowerHost) map.get("host");
			if (!host.vmCreate(vm)) {
				Log.printLine("Couldn't restore VM #" + vm.getId() + " on host #" + host.getId());
				System.exit(0);
			}
			getVmTable().put(vm.getUid(), host);
		}
	}

	/**
	 * Gets the power after allocation.
	 * 
	 * @param host the host
	 * @param vm the vm
	 * 
	 * @return the power after allocation
	 */
	protected double getPowerAfterAllocation(PowerHost host, Vm vm) {
		double power = 0;
		try {
			power = host.getPowerModel().getPower(getMaxUtilizationAfterAllocation(host, vm));
		} catch (Exception e) {
			e.printStackTrace();
			System.exit(0);
		}
		return power;
	}

	/**
	 * Gets the power after allocation. We assume that load is balanced between PEs. The only
	 * restriction is: VM's max MIPS < PE's MIPS
	 * 
	 * @param host the host
	 * @param vm the vm
	 * 
	 * @return the power after allocation
	 */
	protected double getMaxUtilizationAfterAllocation(PowerHost host, Vm vm) {
		double requestedTotalMips = vm.getCurrentRequestedTotalMips();
		double hostUtilizationMips = getUtilizationOfCpuMips(host);
		double hostPotentialUtilizationMips = hostUtilizationMips + requestedTotalMips;
		double pePotentialUtilization = hostPotentialUtilizationMips / host.getTotalMips();
		return pePotentialUtilization;
	}
	
	/**
	 * Gets the utilization of the CPU in MIPS for the current potentially allocated VMs.
	 *
	 * @param host the host
	 *
	 * @return the utilization of the CPU in MIPS
	 */
	protected double getUtilizationOfCpuMips(PowerHost host) {
		double hostUtilizationMips = 0;
		for (Vm vm2 : host.getVmList()) {
			if (host.getVmsMigratingIn().contains(vm2)) {
				// calculate additional potential CPU usage of a migrating in VM
				hostUtilizationMips += host.getTotalAllocatedMipsForVm(vm2) * 0.9 / 0.1;
			}
			hostUtilizationMips += host.getTotalAllocatedMipsForVm(vm2);
		}
		return hostUtilizationMips;
	}

	/**
	 * Gets the utilization of the IO in IOPS for the current potentially allocated VMs.
	 *
	 * @param host the host
	 *
	 * @return the utilization of the IO in IOPS
	 */
	protected double getUtilizationOfIops(PowerHost host) {
		double hostUtilizationIops = 0;
		for (Vm vm2 : host.getVmList()) {
			hostUtilizationIops += host.getAllocatedIopsForVm(vm2);
		}
		return hostUtilizationIops;
	}

	
	/**
	 * Gets the saved allocation.
	 * 
	 * @return the saved allocation
	 */
	protected List<Map<String, Object>> getSavedAllocation() {
		return savedAllocation;
	}

	/**
	 * Gets the utilization history.
	 * 
	 * @return the utilization history
	 */
	public Map<Integer, List<Double>> getUtilizationHistoryIo() {
		return utilizationHistoryIo;
	}

	/**
	 * Gets the metric history.
	 * 
	 * @return the metric history
	 */
	public Map<Integer, List<Double>> getMetricHistoryIo() {
		return metricHistoryIo;
	}

	/**
	 * Gets the time history.
	 * 
	 * @return the time history
	 */
	public Map<Integer, List<Double>> getTimeHistoryIo() {
		return timeHistoryIo;
	}

	/**
	 * Gets the execution time history vm selection.
	 * 
	 * @return the execution time history vm selection
	 */
	public List<Double> getExecutionTimeHistoryVmSelection() {
		return executionTimeHistoryVmSelection;
	}

	/**
	 * Gets the execution time history host selection.
	 * 
	 * @return the execution time history host selection
	 */
	public List<Double> getExecutionTimeHistoryHostSelection() {
		return executionTimeHistoryHostSelection;
	}


	private List<Double> getExecutionTimeHistoryHostSelectionIo() {
		return executionTimeHistoryHostSelectionIo;
	}
	
	/**
	 * Gets the execution time history vm reallocation.
	 * 
	 * @return the execution time history vm reallocation
	 */
	public List<Double> getExecutionTimeHistoryVmReallocation() {
		return executionTimeHistoryVmReallocation;
	}

	/**
	 * Gets the execution time history total.
	 * 
	 * @return the execution time history total
	 */
	public List<Double> getExecutionTimeHistoryTotal() {
		return executionTimeHistoryTotal;
	}

	
	public double getWeightMipsUtil() {
		return weightMipsUtil;
	}

	public void setWeightMipsUtil(double weightMipsUtil) {
		this.weightMipsUtil = weightMipsUtil;
	}

	public double getWeightIopsUtil() {
		return weightIopsUtil;
	}

	public void setWeightIopsUtil(double weightIopsUtil) {
		this.weightIopsUtil = weightIopsUtil;
	}

	public PowerVmSelectionPolicyIo getVmSelectionPolicyIo() {
		return vmSelectionPolicyIo;
	}

	public void setVmSelectionPolicyIo(PowerVmSelectionPolicyIo vmSelectionPolicyIo) {
		this.vmSelectionPolicyIo = vmSelectionPolicyIo;
	}
	
}