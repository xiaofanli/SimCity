package nju.ics.lixiaofan.event;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

import nju.ics.lixiaofan.control.Delivery.DeliveryTask;

public class Event implements Cloneable{
	public String time = null;
	public Type type = null;
	public String car = null;
	public String location = null;
	public Set<String> crashedCars = null;// only for CAR_CRASH type
	public DeliveryTask dtask = null;//only for DELIVERY type
	public int cmd = -1;//only for REQUEST and RESPONSE type
	public static SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd hh:mm:ss:SSS");
	
	public static enum Type{
		ALL,
		//physical events
		CAR_ENTER, CAR_LEAVE, CAR_CRASH, CAR_MOVE, CAR_STOP, ADD_CAR, REMOVE_CAR,
		//logical events
		DELIVERY_RELEASED, DELIVERY_COMPLETED, CAR_START_LOADING, CAR_END_LOADING,
		CAR_START_UNLOADING, CAR_END_UNLOADING, CAR_SEND_REQUEST, CAR_RECV_RESPONSE,
		CAR_REACH_DEST
	}
	
	public Event(Type type, String car, String location) {
		this.time = sdf.format(new Date());
		this.type = type;
		this.car = car;
		this.location = location;
	}

	public Event(Type type, Set<String> crashedCars, String location) {
		this.time = sdf.format(new Date());
		this.type = type;
		this.crashedCars = crashedCars;
		this.location = location;
	}
	
	public Event(Type type, DeliveryTask dtask) {
		this.time = sdf.format(new Date());
		this.type = type;
		this.dtask = dtask;
	}

	public Event(Type type, String car, String location, int cmd) {
		this.time = sdf.format(new Date());
		this.type = type;
		this.car = car;
		this.location = location;
		this.cmd = cmd;
	}

	@Override
	protected Event clone() throws CloneNotSupportedException {
		Event event = (Event) super.clone();
		if(event.crashedCars != null){
			Set<String> crashedCars = new HashSet<String>();
			crashedCars.addAll(event.crashedCars);
			event.crashedCars = crashedCars;
		}
		return event;
	}
}
