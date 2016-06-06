package nju.ics.lixiaofan;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.sun.org.glassfish.gmbal.Description;

import nju.ics.lixiaofan.car.Car;
import nju.ics.lixiaofan.car.Command;
import nju.ics.lixiaofan.car.RCClient;
import nju.ics.lixiaofan.dashboard.Dashboard;
import nju.ics.lixiaofan.resource.Resource;

public class SelfCheck{
	private final Dashboard dashboard;
	private	final Object OBJ = new Object();
	private final Map<String, Boolean> deviceStatus = new HashMap<>();
	
	/**
	 * This method will block until all devices are ready
	 * @param dashboard
	 */
	public SelfCheck(Dashboard dashboard) {
		this.dashboard = dashboard;
		deviceStatus.put(RCClient.name, false);
		for(String name : Resource.getBricks())
			deviceStatus.put(name, false);
		for(Car car : Resource.getCars())
			deviceStatus.put(car.name, false);
		
		new RCChecking().start();
		for(String name : Resource.getBricks())
			new BrickChecking(name).start();
		
		synchronized (OBJ) {
			try {
				OBJ.wait();//wait until all devices are ready
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
	}
	
	public boolean allReady(){
		for(boolean b : deviceStatus.values())
			if(!b)	return false;
		return true;
	}
	
	class RCChecking extends Thread{
		public void run() {
			setName("RC Checking");
			boolean connected = false, started = false;
			while(true){
				while(!RCClient.tried){
					synchronized (RCClient.TRIED_LOCK) {
						try {
							RCClient.TRIED_LOCK.wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
				RCClient.tried = false;
				connected = RCClient.isConnected();
				if(connected && !started){//only start car checking threads once
					started = true;
					for(Car car :Resource.getCars())
						new CarChecking(car).start();
				}
				if(connected ^ deviceStatus.get(RCClient.name)){
					dashboard.setDeviceStatus(RCClient.name, connected);
					if(allReady()){//true -> false
						deviceStatus.put(RCClient.name, connected);
						if(!Main.initial){//TODO suspend
							
						}
					}
					else{
						deviceStatus.put(RCClient.name, connected);
						if(allReady()){//false -> true
							if(Main.initial)
								synchronized (OBJ) {
									OBJ.notify();
								}
							else{//TODO resume
								
							}
						}
					}
				}
			}
		}
	}
	
	class CarChecking extends Thread{
		private final Car car;
		public CarChecking(Car car) {
			this.car = car;
		}
		public void run() {
			setName("Car Checking: " + car.name);
			while(true){
				if(!car.isConnected)
					Command.connect(car);
				while(!car.tried){
					synchronized (car.TRIED_LOCK) {
						try {
							car.TRIED_LOCK.wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
				car.tried = false;
				if(car.isConnected ^ deviceStatus.get(car.name)){
					dashboard.setDeviceStatus(car.name, car.isConnected);
					if(allReady()){//true -> false
						deviceStatus.put(car.name, car.isConnected);
						if(!Main.initial){//TODO suspend
							
						}
					}
					else{
						deviceStatus.put(car.name, car.isConnected);
						if(allReady()){//false -> true
							if(Main.initial)
								synchronized (OBJ) {
									OBJ.notify();
								}
							else{//TODO resume
								
							}
						}
					}
				}
			}
		}
	}
	
	class BrickChecking extends Thread{
		private final String name, addr;
		private final Session session;
		byte[] buf = new byte[1024];
		public BrickChecking(String name) {
			this.name = name;
			addr = Resource.getBrickAddr(name);
			if(addr == null){
				System.err.println("Brick " + name + " has no address");
				System.exit(-1);
			}
			session = Resource.getSession(name);
		}
		public void run() {
			setName("Brick Checking: " + name);
			while(true){
				//first, check if brick is reachable
				boolean connected = false;
				while(!connected){
					try {
						connected = InetAddress.getByName(addr).isReachable(5000);
					} catch (IOException e) {
						e.printStackTrace();
						connected = false;
					}
					dashboard.setDeviceStatus(name + " conn", connected);
				}
				
				//second, start sample program in brick
				Channel channel = null;
				try {
					session.connect();
					channel = session.openChannel("exec");
					((ChannelExec) channel).setCommand("./start.sh");
					channel.setInputStream(null);
					((ChannelExec) channel).setErrStream(System.err);
					channel.connect();
					channel.getInputStream().read();//assure sample program is started
					channel.disconnect();
				} catch (JSchException | IOException e) {
					e.printStackTrace();
					channel.disconnect();
					session.disconnect();
					continue;
				}
				
				//third, check if sample program is running
				boolean sampling = false;
				while(true){
					try {
						channel = session.openChannel("exec");
						((ChannelExec) channel).setCommand("ps -ef | grep 'python sample.py' | grep -v grep");
						channel.setInputStream(null);
						((ChannelExec) channel).setErrStream(System.err);
						channel.connect();
						sampling = channel.getInputStream().read() > 0;
						channel.disconnect();
					} catch (JSchException | IOException e) {
						e.printStackTrace();
						channel.disconnect();
						session.disconnect();
						sampling = false;
					}
					finally{
						if(sampling ^ deviceStatus.get(name)){
							dashboard.setDeviceStatus(name + " sample", sampling);
							if(allReady()){//true -> false
								deviceStatus.put(name, sampling);
								if(!Main.initial){//TODO suspend
									
								}
							}
							else{
								deviceStatus.put(name, sampling);
								if(allReady()){//false -> true
									if(Main.initial)
										synchronized (OBJ) {
											OBJ.notify();
										}
									else{//TODO resume
										
									}
								}
							}
						}
						if(!sampling)
							break;
					}
					try {
						Thread.sleep(1000);
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
		}
	}
}
