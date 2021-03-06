package nju.xiaofanli;

import nju.xiaofanli.application.Delivery;
import nju.xiaofanli.dashboard.Road;
import nju.xiaofanli.dashboard.TrafficMap;
import nju.xiaofanli.consistency.middleware.Middleware;
import nju.xiaofanli.dashboard.Dashboard;
import nju.xiaofanli.device.SelfCheck;
import nju.xiaofanli.device.car.Car;
import nju.xiaofanli.device.car.Command;
import nju.xiaofanli.device.sensor.BrickHandler;
import nju.xiaofanli.device.sensor.Sensor;
import nju.xiaofanli.util.Counter;
import nju.xiaofanli.util.StyledText;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * INIT <-> NORMAL
 * <p>
 * NORMAL <-> RESET, RELOCATE, SUSPEND
 * <p>
 * RESET, RELOCATE <-> SUSPEND
 * @author leslie
 *
 */
public class StateSwitcher {
    private static volatile State state = State.INIT;
    private static ConcurrentHashMap<Thread, Boolean> threadStatus = new ConcurrentHashMap<>();//reset or not

    private enum State {INIT, NORMAL, RESET, SUSPEND, RELOCATE}

    private StateSwitcher(){}

    public static boolean isInitializing() {
        return state == State.INIT;
    }

    public static boolean isNormal(){
        return state == State.NORMAL;
    }

    public static boolean isResetting(){
        return state == State.RESET;
    }

    public static boolean isSuspending(){
        return state == State.SUSPEND;
    }

    public static boolean isRelocating() {
        return state == State.RELOCATE;
    }

    private static void setState(State s){
        //noinspection SynchronizeOnNonFinalField
        synchronized (state) {
            state = s;
        }
    }

    public static void register(Thread thread){
        threadStatus.put(thread, false);
    }

    public static void unregister(Thread thread){
        threadStatus.remove(thread);
        if(isResetting() && allReset())
            wakeUp(ResetTask.OBJ);
    }

    public static boolean isThreadReset(Thread thread){
        if(threadStatus.get(thread))
            return true;
        threadStatus.put(thread, true);
        if(isResetting() && allReset())
            wakeUp(ResetTask.OBJ);
        return false;
    }

    private static boolean allReset(){
        for(Boolean b : threadStatus.values()){
            if(!b)
                return false;
        }
        return true;
    }

    private static void resetThreadStatus(){
        for(Thread t : threadStatus.keySet())
            threadStatus.put(t, false);
    }

    private static void interruptAll(){
        threadStatus.keySet().forEach(Thread::interrupt);
    }

    private static void wakeUp(final Object obj){
        //noinspection SynchronizationOnLocalVariableOrMethodParameter
        synchronized (obj) {
            obj.notify();
        }
    }

    public static void init() {
        setState(State.INIT);
    }

    public static void finishInit() {
        setState(State.NORMAL);
    }

    public static void startResetting(boolean locateAllCars, boolean locateNoCars, boolean locationAlreadyKnown) {
        startResetting(locateAllCars, locateNoCars, locationAlreadyKnown, null);
    }

    public static void startResetting(boolean locateAllCars, boolean locateNoCars, boolean locationAlreadyKnown, String disabledScenario) {
        resetTask.locateAllCars = locateAllCars;
        resetTask.locateNoCars = locateNoCars;
        resetTask.locationAlreadyKnown = locationAlreadyKnown;
        resetTask.disabledScenario = disabledScenario;
        Resource.execute(resetTask);
    }

    public static void setLastStopCmdTime(long time){
        resetTask.lastStopCmdTime = time;
    }

    public static void detectedBy(Sensor sensor) {
        if (isResetting())
            ResetTask.detectedBy(sensor);
        else if (isRelocating())
            Relocation.detectedBy(sensor);
    }

    private static ResetTask resetTask = new ResetTask();
    private static class ResetTask implements Runnable {
        private static final Object OBJ = new Object();
        private final long maxWaitingTime = 1000;
        private Set<Car> cars2locate = new HashSet<>();
        private Map<Car, CarInfo> carInfo = new HashMap<>();
        private boolean locateAllCars, locateNoCars, locationAlreadyKnown;
        private String disabledScenario;
        private Car car2locate = null;
        private long lastStopCmdTime;
        private Set<Car> locatedCars = new HashSet<>();

        private ResetTask() {
        }

        public void run() {
            checkIfSuspended();
            setState(State.RESET);
            Dashboard.enableCtrlUI(false);
            //first step: stop the world
            interruptAll();
            Command.stopAllCars();
            Command.silenceAllCars();
            if(locateAllCars) {//all cars need to be located
                cars2locate.addAll(Resource.getConnectedCars());
            }
            else if (!locateNoCars) {//Only moving cars and crashed cars need to be located
                for(Car car :Resource.getConnectedCars()){
                    if(car.getState() != Car.STOPPED)
                        cars2locate.add(car);
                    if(car.getRealLoc() != null){
                        if(car.getRealLoc().carsWithoutFake.size() > 1)
                            cars2locate.addAll(car.getRealLoc().carsWithoutFake);
                    }
                }
            }
            //store the info of cars that have no need to locate
            for(Car car :Resource.getConnectedCars()){
                if(cars2locate.contains(car))
                    continue;
                carInfo.put(car, new CarInfo(car.getRealLoc(), car.getRealDir()));
            }

            while(!allReset()){
                try {
                    synchronized (OBJ) {
                        OBJ.wait();//wait for all threads reaching their safe points
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    checkIfSuspended();
                }
            }

            long duration = maxWaitingTime - (System.currentTimeMillis() - lastStopCmdTime);
            while(duration > 0){
                long startTime = System.currentTimeMillis();
                try {
                    Thread.sleep(duration);//wait for all cars to stop
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    checkIfSuspended();
                }
                finally{
                    duration -= System.currentTimeMillis() - startTime;
                }
            }
            //second step: clear all statuses
            checkIfSuspended();
            List<Car> cars2locateInOrder = locationAlreadyKnown ? getTopologicalOrder(cars2locate) : new ArrayList<>(cars2locate);
            TrafficMap.reset();
            Middleware.reset();
            Delivery.reset();

            //third step: resolve the inconsistency
            checkIfSuspended();
            //for the car that does not need locating, just restore its loc and dir
            for(Map.Entry<Car, CarInfo> e : carInfo.entrySet())
                e.getValue().restore(e.getKey());
            //locate cars one by one

            for(Car car : cars2locateInOrder){
                car2locate = car;
                Command.drive(car);
                while(!locatedCars.contains(car)){
                    try {
                        synchronized (OBJ) {
                            OBJ.wait();// wait for any readings from sensors
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        checkIfSuspended();
                    }
                }

                duration = maxWaitingTime - (System.currentTimeMillis() - lastStopCmdTime);
                while(duration > 0){
                    long startTime = System.currentTimeMillis();
                    try {
                        Thread.sleep(duration);//wait for the car to stop
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                        checkIfSuspended();
                    }
                    finally{
                        duration -= System.currentTimeMillis() - startTime;
                    }
                }
            }
            //fourth step: recover the world
            checkIfSuspended();
            resetThreadStatus();
            car2locate = null;
            cars2locate.clear();
            locatedCars.clear();
            carInfo.clear();

            Dashboard.enableCtrlUI(true);
            Dashboard.reset();
            setState(State.NORMAL);

            if (disabledScenario != null) {
                switch (disabledScenario) {
                    case "ideal":
                        Dashboard.log(new StyledText("Ideal scenario is disabled.\n"), new StyledText("理想场景已关闭。\n")); break;
                    case "noisy":
                        Dashboard.log(new StyledText("Noisy scenario is disabled.\n"), new StyledText("包含错误的场景已关闭。\n")); break;
                    case "fixed":
                        Dashboard.log(new StyledText("Fixed scenario is disabled.\n"), new StyledText("修复错误的场景已关闭。\n")); break;
                }
            }
            Dashboard.log(new StyledText("Initialization is complete.\n"), new StyledText("初始化完成。\n"));

            TrafficMap.checkCrash();
        }

        static void detectedBy(Sensor sensor) {
            if (isResetting()) {
                Car car = resetTask.car2locate;
                if (car != null && car.loc == null) {//still not located, then locate it
                    resetTask.car2locate = null;
                    Command.stop(car);
                    car.initLocAndDir(sensor);
                    resetTask.locatedCars.add(car);
                    wakeUp(ResetTask.OBJ);
                }
            }
        }

        private List<Car> getTopologicalOrder(Set<Car> cars) {
            List<Car> res = new ArrayList<>();
            if (cars == null || cars.isEmpty())
                return res;

            Map<Car, Set<Car>> indegree = new HashMap<>(), outdegree = new HashMap<>();
            for (Car car : cars) {
                Map<TrafficMap.Direction, Sensor> adjSensors = car.getRealLoc().adjSensors;
                TrafficMap.Direction dir = car.getRealDir();
                Road nextRoad = adjSensors.get(dir).nextRoad;//car.getRealLoc().adjSensors.get(car.getRealDir()).nextRoad;
                for (Car car2 : nextRoad.carsWithoutFake) {
                    if (!indegree.containsKey(car))
                        indegree.put(car, new HashSet<>());
                    indegree.get(car).add(car2);

                    if (!outdegree.containsKey(car2))
                        outdegree.put(car2, new HashSet<>());
                    outdegree.get(car2).add(car);
                }
            }

            Queue<Car> zeroIndegree = new LinkedList<>();
            for (Car car : cars) {
                if (!indegree.containsKey(car) || indegree.get(car).isEmpty()) {
                    indegree.remove(car);
                    zeroIndegree.add(car);
                }
            }

            while (!zeroIndegree.isEmpty()) {
                Car in = zeroIndegree.poll();
                res.add(in);
                if (outdegree.containsKey(in)) {
                    for (Car out : outdegree.get(in)) {
                        indegree.get(out).remove(in);
                        if (indegree.get(out).isEmpty()) {
                            indegree.remove(out);
                            zeroIndegree.add(out);
                        }
                    }
                    outdegree.remove(in);
                }
            }

            if (!indegree.isEmpty() || !outdegree.isEmpty()) {
                try {
                    throw new Exception("graph has at least one cycle");
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            return res;
        }

        private class CarInfo{
            private Sensor sensor;
            private CarInfo(Road loc, TrafficMap.Direction dir){
                sensor = loc.adjSensors.get(dir).prevSensor;
            }

            private void restore(Car car){
                car.initLocAndDir(sensor);
            }
        }
    }

    private static State prevState = null;
    private static final Lock SUSPEND_LOCK = new ReentrantLock();
    private static final Object SUSPEND_OBJ = new Object();
    private static Set<Car> movingCars = new HashSet<>(), whistlingCars = new HashSet<>();
    private static boolean isSuspended = false;
    public static void suspend(){
        if(!isSuspended && !SelfCheck.allReady()) {
            SUSPEND_LOCK.lock();
            if (!isSuspended && !SelfCheck.allReady()) {
                System.out.println("*** SUSPEND ***");
                isSuspended = true;
                prevState = state;
                setState(State.SUSPEND);
//                try {
//                    Thread.sleep(1000); //wait for working threads to reach their safe points
//                } catch (InterruptedException e) {
//                    e.printStackTrace();
//                }
                for (Car car : Resource.getConnectedCars()) {
                    if (car.trend == Car.MOVING)
                        movingCars.add(car);
                    Command.stop(car);
                    if (car.lastHornCmd == Command.HORN_ON && car.isHornOn)
                        whistlingCars.add(car);
                    Command.silence(car);
                }
                if (prevState == State.NORMAL)
                    Dashboard.enableCtrlUI(false);
                Dashboard.showDeviceDialog(false);

                Counter.stopTimer();
            }
            SUSPEND_LOCK.unlock();
        }
    }

    public static void resume(){
        if(isSuspended && SelfCheck.allReady()) {
            SUSPEND_LOCK.lock();
            if (isSuspended && SelfCheck.allReady()) {
                System.out.println("*** RESUME ***");
                isSuspended = false;
                Dashboard.closeDeviceDialog();
                if (prevState == State.NORMAL)
                    Dashboard.enableCtrlUI(true);

                movingCars.forEach(Command::drive);
                whistlingCars.forEach(Command::whistle);
                setState(prevState);
                interruptAll(); //must invoked after state changed back, drive all threads away from safe points
                if (prevState == State.RESET || prevState == State.RELOCATE) {
                    synchronized (SUSPEND_OBJ) {
                        SUSPEND_OBJ.notifyAll();
                    }
                }
                movingCars.clear();
                whistlingCars.clear();
                prevState = null;

                Counter.startTimer();
            }
            SUSPEND_LOCK.unlock();
        }
    }

    private static void checkIfSuspended(){
        while(isSuspending()) {
            try {
                synchronized (SUSPEND_OBJ) {
                    SUSPEND_OBJ.wait();
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * @param car2relocate the car needed to relocate
     * @param sensor the sensor in font of the car's known location
     * @param detected whether car2relocate is detected by the next one of sensor
     */
    public static void startRelocating(Car car2relocate, Sensor sensor, boolean detected) {
        if (isResetting())
            return;
        Relocation.add(car2relocate, sensor, detected);
    }

    static void startRelocationThread() {
        if (!relocation.isAlive())
            relocation.start();
    }


    private static Relocation relocation = new Relocation();
    public static class Relocation extends Thread {
        private static final Object OBJ = new Object();
        private static final Queue<Request> queue = new LinkedList<>();
        private static final Set<Car> cars2relocate = new HashSet<>();
        private static Car car2relocate = null;
        private static Sensor locatedSensor = null;
        private static final Set<Car> movingCars = new HashSet<>(), whistlingCars = new HashSet<>();
        private static boolean isPreserved = false, isInterested = false, areAllCarsStopped = false;
        private static final Set<Sensor> interestedSensors = new HashSet<>();

        private Relocation(){}
        @Override
        public void run() {
            setName("Relocation Thread");
            //noinspection InfiniteLoopStatement
            while (true) {
                while (queue.isEmpty()) {
                    if (isPreserved) {
                        checkIfSuspended();
                        isPreserved = false;
                        cars2relocate.clear();
                        movingCars.forEach(Command::drive);
                        whistlingCars.forEach(Command::whistle);
                        Dashboard.closeRelocationDialog();
                        Dashboard.enableCtrlUI(true);
                        movingCars.clear();
                        whistlingCars.clear();
                        setState(StateSwitcher.State.NORMAL);
                        System.out.println("switch state to normal");
                        interruptAll();

                        Counter.startTimer();
                    }

                    try {
                        synchronized (queue) {
                            if (queue.isEmpty())
                                queue.wait();
                        }
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                checkIfSuspended();

//                if (!isPreserved) {
//                    isPreserved = true;
//                    setState(StateSwitcher.State.RELOCATE);
//                    System.out.println("switch state to relocation");
//                    Dashboard.enableCtrlUI(false);
//                    try {
//                        Thread.sleep(1000); //wait for working threads to reach their safe points
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                    checkIfSuspended();
//
//                    for (Car car : Resource.getConnectedCars()) {
//                        if (car.trend == Car.MOVING)
//                            movingCars.add(car);
//                        Command.stop(car);
//                        if (car.lastHornCmd == Command.HORN_ON && car.isHornOn)
//                            whistlingCars.add(car);
//                        Command.silence(car);
//                    }
//
//                    try {
//                        Thread.sleep(1000); //wait for all cars to stop
//                    } catch (InterruptedException e) {
//                        e.printStackTrace();
//                    }
//                    checkIfSuspended();
//                }
                if (!areAllCarsStopped) {
                    try {
                        Thread.sleep(1000); // wait for all cars to stop
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    areAllCarsStopped = true;
                    checkIfSuspended();
                }

                Request r;
                synchronized (queue) {
                    r = queue.poll();
                }
                if (r == null || r.car2relocate == null || r.sensor == null)
                    continue;

                if (r.sensor.nextRoad.isStraight(r.sensor.getNextRoadDir())
                        && r.sensor.prevRoad.isStraight(r.sensor.prevSensor.getNextRoadDir())) { //if both roads are straight, use backward relocation
                    backwardRelocate(r.car2relocate, r.sensor);
                }
                else { //if either one is curved, use forward relocation
                    forwardRelocate(r.car2relocate, r.sensor, r.detected);
                }
            }
        }

        private static void forwardRelocate(Car car, Sensor sensor, boolean detectedByNextNextSensor) {
            Map<Car, Sensor> relocatedCars = new HashMap<>();
            forwardRelocateRecursively(car, sensor, true, detectedByNextNextSensor, relocatedCars);
            relocatedCars.forEach(BrickHandler::triggerEventAfterEntering); //each car should trigger events when it's detected by the LAST sensor
        }

        private static void forwardRelocateRecursively(Car car, Sensor sensor, boolean knownLost, boolean detectedByNextNextSensor, final Map<Car, Sensor> relocatedCars) {
            checkIfSuspended();
            synchronized (queue) {
                synchronized (cars2relocate) {
                    cars2relocate.add(car);
                }
                for (Iterator<Request> iter = queue.iterator();iter.hasNext();) {
                    Request r = iter.next();
                    if (r.car2relocate == car) {
                        iter.remove();
                        knownLost = true;
                        if (r.detected)
                            detectedByNextNextSensor = true;
                    }
                }
            }

            Road nextRoad = sensor.nextRoad;
            while (!nextRoad.carsWithoutFake.isEmpty()) {
                Car car1 = nextRoad.carsWithoutFake.peek();
                forwardRelocateRecursively(car1, nextRoad.adjSensors.get(car1.getRealDir()), relocatedCars);
                if (!nextRoad.carsWithoutFake.isEmpty())
                    System.err.println("There still is/are car(s) at " + nextRoad.name + " in front of the relocated car " + car.name);
            }

            Sensor nextNextSensor = sensor.nextSensor;
            Sensor nextNextNextSensor = sensor.nextSensor.nextSensor;
            Road nextNextRoad = nextNextSensor.nextRoad;
            Road nextNextNextRoad = nextNextNextSensor.nextRoad;

            if (knownLost) {
                while (!nextNextRoad.carsWithoutFake.isEmpty()) {
                    Car car1 = nextNextRoad.carsWithoutFake.peek();
                    forwardRelocateRecursively(car1, nextNextRoad.adjSensors.get(car1.getRealDir()), relocatedCars);
                    if (!nextNextRoad.carsWithoutFake.isEmpty())
                        System.err.println("There still is/are car(s) at " + nextNextRoad.name);
                }
            }

            if (detectedByNextNextSensor) {
                while (!nextNextNextRoad.carsWithoutFake.isEmpty()) {
                    Car car1 = nextNextNextRoad.carsWithoutFake.peek();
                    forwardRelocateRecursively(car1, nextNextNextRoad.adjSensors.get(car1.getRealDir()), relocatedCars);
                    if (!nextNextNextRoad.carsWithoutFake.isEmpty())
                        System.err.println("2There still is/are car(s) at " + nextNextNextRoad.name);
                }
            }

            car2relocate = car;
            long prevTimeout = sensor.prevRoad.timeouts.get(sensor.prevSensor.getNextRoadDir()).get(car.url);
            long nextTimeout = nextRoad.timeouts.get(sensor.getNextRoadDir()).get(car.url);
            long timeout = knownLost ? Math.max(prevTimeout, nextTimeout) : prevTimeout;
            Dashboard.clearRelocationDialog();
            Dashboard.showRelocationDialog(car);
            //relocate car using sensor or both sensor and sensor.nextSensor if already in relocation
            interestedSensors.clear();
            interestedSensors.add(sensor);
            if (knownLost)
                interestedSensors.add(nextNextSensor);
            if (detectedByNextNextSensor)
                interestedSensors.add(nextNextNextSensor);
            isInterested = true;
            Command.drive(car);
            if (locatedSensor == null) {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
//                    e.printStackTrace();
                }
            }
            Command.stop(car);
            isInterested = false;
            interestedSensors.clear();
            checkIfSuspended();

            if (locatedSensor == null) { // timeout reached, relocation failed
                Dashboard.showRelocationDialog(car, false, sensor.prevRoad);
                synchronized (OBJ) {
                    try {
                        OBJ.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                checkIfSuspended();
                // the car is manually relocated
                car.timeout = sensor.prevRoad.timeouts.get(sensor.prevSensor.getNextRoadDir()).get(car.url); // reset its timeout
            }
            else {
                Dashboard.showRelocationDialog(car, true, null);
                if (locatedSensor == sensor) {
                    //enter next road
                    sensor.state = Sensor.UNDETECTED;
//                    BrickHandler.switchState(locatedSensor, 0, System.currentTimeMillis());
                    Middleware.checkConsistency(car.name, Car.MOVING, sensor.prevRoad.name, nextRoad.name, nextNextRoad.name,
                            System.currentTimeMillis(), car, sensor, car.hasPhantom(), false);

                    relocatedCars.put(car, sensor);
                }
                else if (locatedSensor == sensor.nextSensor) {
                    //enter next road
                    sensor.state = Sensor.UNDETECTED;
                    Middleware.checkConsistency(car.name, Car.MOVING, sensor.prevRoad.name, nextRoad.name, nextNextRoad.name,
                            System.currentTimeMillis()-400, car, sensor, car.hasPhantom(), false);
                    //enter next next road
                    sensor.nextSensor.state = Sensor.UNDETECTED;
                    Middleware.checkConsistency(car.name, Car.MOVING, nextRoad.name, nextNextRoad.name, nextNextNextRoad.name,
                            System.currentTimeMillis(), car, nextNextSensor, car.hasPhantom(), false);

                    relocatedCars.put(car, nextNextSensor);
                }
                else if (locatedSensor == sensor.nextSensor.nextSensor) {
                    //enter next road
                    sensor.state = Sensor.UNDETECTED;
                    Middleware.checkConsistency(car.name, Car.MOVING, sensor.prevRoad.name, nextRoad.name, nextNextRoad.name,
                            System.currentTimeMillis()-800, car, sensor, car.hasPhantom(), false);
                    //enter next next road
                    sensor.nextSensor.state = Sensor.UNDETECTED;
                    Middleware.checkConsistency(car.name, Car.MOVING, nextRoad.name, nextNextRoad.name, nextNextNextRoad.name,
                            System.currentTimeMillis()-400, car, nextNextSensor, car.hasPhantom(), false);
                    //enter next next next road
                    sensor.nextSensor.nextSensor.state = Sensor.UNDETECTED;
                    Middleware.checkConsistency(car.name, Car.MOVING, nextNextRoad.name, nextNextNextRoad.name, nextNextNextSensor.nextSensor.nextRoad.name,
                            System.currentTimeMillis(), car, nextNextNextSensor, car.hasPhantom(), false);

                    relocatedCars.put(car, nextNextNextSensor);
                }
                Counter.increaseSuccessfulRelocation();
            }
            Counter.increaseRelocation();
            car2relocate = null;
            locatedSensor = null;
        }

        private static void forwardRelocateRecursively(Car car, Sensor sensor, final Map<Car, Sensor> relocatedCars) {
            forwardRelocateRecursively(car, sensor, false, false, relocatedCars);
        }

        private static void backwardRelocate(Car car, Sensor sensor) {
            checkIfSuspended();
            car2relocate = car;
            long timeout = (long) (sensor.prevRoad.timeouts.get(sensor.prevSensor.getNextRoadDir()).get(car.url) * 1.2);// car moves slower when moving backward, so better multiply by a factor
            Dashboard.clearRelocationDialog();
            Dashboard.showRelocationDialog(car);
            interestedSensors.clear();
            interestedSensors.add(sensor.prevSensor);
            interestedSensors.add(sensor);
            isInterested = true;
            Command.back(car);
            if (locatedSensor == null) {
                try {
                    Thread.sleep(timeout);
                } catch (InterruptedException e) {
//                                e.printStackTrace();
                }
            }
            Command.stop(car);
            isInterested = false;
            interestedSensors.clear();
            checkIfSuspended();

            if (locatedSensor == null) { // timeout reached, relocation failed
                Dashboard.showRelocationDialog(car, false, sensor.prevRoad);
                synchronized (OBJ) {
                    try {
                        OBJ.wait();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
                checkIfSuspended();
                // the car is manually relocated
                car.timeout = sensor.prevRoad.timeouts.get(sensor.prevSensor.getNextRoadDir()).get(car.url); // reset its timeout
            }
            else {
                Dashboard.showRelocationDialog(car, true, null);
                if (locatedSensor == sensor) {
                    sensor.state = Sensor.UNDETECTED;
//                    BrickHandler.switchState(locatedSensor, 0, System.currentTimeMillis());
                    Middleware.checkConsistency(car.name, Car.MOVING, sensor.prevRoad.name, sensor.nextRoad.name,
                            sensor.nextSensor.nextRoad.name, System.currentTimeMillis(), car, sensor, car.hasPhantom(), true);
                }
                else {
                    car.timeout = sensor.prevRoad.timeouts.get(sensor.prevSensor.getNextRoadDir()).get(car.url); // reset its timeout
                }
                Counter.increaseSuccessfulRelocation();
            }
            Counter.increaseRelocation();
            car2relocate = null;
            locatedSensor = null;
        }

        public static void manuallyRelocated() {
            synchronized (OBJ) {
                OBJ.notify();
            }
        }

        static void detectedBy(Sensor sensor) {
            if (isInterestedSensor(sensor)) {
                isInterested = false;
                interestedSensors.clear();
                Command.stop(Relocation.car2relocate);
                Relocation.locatedSensor = sensor;
                relocation.interrupt();
            }
        }

        public static boolean isInterestedSensor(Sensor sensor) {
            return isRelocating() && isInterested && interestedSensors.contains(sensor);
        }

        public static void add(Car car2relocate, Sensor sensor, boolean detected) {
            synchronized (queue) {
                synchronized (cars2relocate) {
                    if (cars2relocate.contains(car2relocate))
                        return;
                    cars2relocate.add(car2relocate);
                }

//                setState(StateSwitcher.State.RELOCATE);
//                System.out.println("switch state to relocation");
                if (!isPreserved) {
                    isPreserved = true;
                    setState(StateSwitcher.State.RELOCATE);
                    System.out.println("switch state to relocation");
                    for (Car car : Resource.getConnectedCars()) {
                        if (car.trend == Car.MOVING)
                            movingCars.add(car);
                        Command.stop(car);
                        if (car.lastHornCmd == Command.HORN_ON && car.isHornOn)
                            whistlingCars.add(car);
                        Command.silence(car);
                    }
                    areAllCarsStopped = false;
                    Dashboard.enableCtrlUI(false);

                    Counter.stopTimer();
                }

                queue.add(new Request(car2relocate, sensor, detected));
                queue.notifyAll();
            }
        }

        private static class Request {
            private Car car2relocate;
            private Sensor sensor;
            private boolean detected;
            Request(Car car2relocate, Sensor sensor, boolean detected) {
                this.car2relocate = car2relocate;
                this.sensor = sensor;
                this.detected = detected;
            }
        }
    }
}
