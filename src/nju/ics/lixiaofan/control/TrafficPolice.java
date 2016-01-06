package nju.ics.lixiaofan.control;

import java.util.LinkedList;
import java.util.Queue;
import nju.ics.lixiaofan.car.Car;
import nju.ics.lixiaofan.car.Command;
import nju.ics.lixiaofan.city.Section;
import nju.ics.lixiaofan.city.TrafficMap;
import nju.ics.lixiaofan.city.Section.Street;
import nju.ics.lixiaofan.event.Event;
import nju.ics.lixiaofan.event.EventManager;

public class TrafficPolice implements Runnable{
	public static Queue<Request> req = new LinkedList<Request>();
	private static Notifier notifier = new Notifier();
	public TrafficPolice() {
		new Thread(notifier).start();
		new Thread(this).start();
	}
	
	public void run() {
		while(true){
			while(req.isEmpty()){
				synchronized (req) {
					try {
						req.wait();
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
				}
			}
			System.out.println("Traffic Police awake!!!");
			Request r = req.poll();
			Section reqSec = r.loc.adjs.get(r.dir);
//			System.out.println(r.loc.name+" "+r.dir);
			if(reqSec == null)
				System.out.println("reqSec is null");
			
			synchronized (reqSec.mutex) {
				if(r.cmd == 0){
					if(r.car.finalState == 0)
						reqSec.removeWaitingCar(r.car);
					else
						reqSec.addWaitingCar(r.car);
					if(r.car == reqSec.permitted[0]){
						reqSec.permitted[0] = null;
						sendNotice(reqSec);
					}
				}
				else if(r.cmd == 1){
					if(reqSec.isOccupied()){
						boolean real = false;
						for(Car car : reqSec.cars)
							if(car.isReal()){
								real = true;
								break;
							}
						if(!real)
							TrafficMap.playErrorSound();
						//tell the car to stop
						System.out.println(r.car.name+" need to STOP!!!");
						reqSec.addWaitingCar(r.car);
						Command.send(r.car, 0);
						Command.send(r.car, Command.HORN);
						//trigger recv response event
						if(EventManager.hasListener(Event.Type.CAR_RECV_RESPONSE))
							EventManager.trigger(new Event(Event.Type.CAR_RECV_RESPONSE, r.car.name, r.car.loc.name, 0));
					}
					else if(reqSec.permitted[0] != null && r.car != reqSec.permitted[0]){
						//tell the car to stop
						System.out.println(r.car.name+" need to STOP!!!2");
						reqSec.addWaitingCar(r.car);
						Command.send(r.car, 0);
						//trigger recv response event
						if(EventManager.hasListener(Event.Type.CAR_RECV_RESPONSE))
							EventManager.trigger(new Event(Event.Type.CAR_RECV_RESPONSE, r.car.name, r.car.loc.name, 0));
					}
					else{
						//tell the car to enter
						System.out.println(r.car.name+" can ENTER!!!");
						reqSec.permitted[0] = r.car;
						Command.send(r.car, 1);
						//trigger recv response event
						if(EventManager.hasListener(Event.Type.CAR_RECV_RESPONSE))
							EventManager.trigger(new Event(Event.Type.CAR_RECV_RESPONSE, r.car.name, r.car.loc.name, 1));
					}
				}
				else if(r.cmd == 2){
					//the car is already stopped
					if(r.car.finalState == 1){
						reqSec.addWaitingCar(r.car);
						System.out.println(r.car.name+" waits for "+reqSec.name);
					}
				}
				else if(r.cmd == 3){
					//inform the traffic police of the entry event
					r.next.removeWaitingCar(r.car);
					if(!reqSec.sameAs(r.next)){
						reqSec.removeWaitingCar(r.car);
						if(r.car == reqSec.permitted[0]){
							reqSec.permitted[0] = null;
							sendNotice(reqSec);
						}
					}
				}
			}
		}
	}

	static class Notifier implements Runnable {
		Queue<Request> req = new LinkedList<Request>();
		
		public void run() {
			while(true){
				while(req.isEmpty()){
					synchronized (req) {
						try {
							req.wait();
						} catch (InterruptedException e) {
							e.printStackTrace();
						}
					}
				}
				
				Request r = req.poll();
				Section loc = r.loc;
				if(loc.isOccupied())
					continue;
				synchronized (loc.mutex) {
					synchronized (loc.waiting) {
						if(loc.waiting.isEmpty())
							loc.permitted[0] = null;
						else{
							Car car = loc.waiting.peek();
							if(car.loc.cars.size() == 1){
								loc.permitted[0] = car;
								Command.send(car, 1);
								System.out.println((loc instanceof Street?"Street ":"Crossing ")+loc.id+" notify "+car.name+" to enter");
								//trigger recv response event
								if(EventManager.hasListener(Event.Type.CAR_RECV_RESPONSE))
									EventManager.trigger(new Event(Event.Type.CAR_RECV_RESPONSE, car.name, car.loc.name, 1));
							}
							else{
								for(Car wcar : loc.waiting)
									if(wcar.loc.cars.size() == 1 || wcar.loc.cars.peek() == wcar){
										loc.permitted[0] = wcar;
										Command.send(wcar, 1);
										System.out.println((loc instanceof Street?"Street ":"Crossing ")+loc.id+" notify "+wcar.name+" to enter");
										//trigger recv response event
										if(EventManager.hasListener(Event.Type.CAR_RECV_RESPONSE))
											EventManager.trigger(new Event(Event.Type.CAR_RECV_RESPONSE, wcar.name, wcar.loc.name, 1));
										break;
									}
							}
						}
					}
				}
			}	
		}
		
		public void sendNotice(Section loc){
			synchronized (req) {
				req.add(new Request(loc));
				req.notify();
			}
		}
	};
	
	public static void sendRequest(Car car, int dir, Section loc, int cmd){
		sendRequest(car, dir, loc, cmd, null);
	}
	
	public static void sendRequest(Car car, int dir, Section loc, int cmd, Section next) {
		synchronized (req) {
			req.add(new Request(car, dir, loc, cmd, next));
			req.notify();
		}
		System.out.println(car.name+" send Request "+cmd+" to Traffic Police");
		//trigger send request event
		if(EventManager.hasListener(Event.Type.CAR_SEND_REQUEST))
			EventManager.trigger(new Event(Event.Type.CAR_SEND_REQUEST, car.name, loc.name, cmd));
	}
	
	public static void sendNotice(Section loc){
		notifier.sendNotice(loc);
	}
	
	private static class Request{
		Car car;
		int dir;
		Section loc, next;
		int cmd;
		public Request(Car car, int dir, Section loc, int cmd, Section next) {
			this.car = car;
			this.dir = dir;
			this.loc = loc;
			this.cmd = cmd;
			this.next = next;
		}
		
		public Request(Section loc) {
			this.loc = loc;
		}
	}
}
