package org.nhl.containing;

import com.jme3.app.SimpleApplication;
import com.jme3.cinematic.MotionPath;
import com.jme3.cinematic.events.MotionEvent;
import com.jme3.input.KeyInput;
import com.jme3.input.controls.ActionListener;
import com.jme3.input.controls.KeyTrigger;
import com.jme3.light.DirectionalLight;
import com.jme3.material.Material;
import com.jme3.math.ColorRGBA;
import com.jme3.math.Quaternion;
import com.jme3.math.Vector3f;
import com.jme3.renderer.RenderManager;
import com.jme3.scene.Geometry;
import com.jme3.scene.Spatial;
import com.jme3.scene.shape.Box;
import com.jme3.texture.Texture;
import com.jme3.util.SkyFactory;
import java.io.IOException;
import org.nhl.containing.areas.*;
import org.nhl.containing.communication.Client;
import org.nhl.containing.vehicles.*;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import org.nhl.containing.communication.messages.ArriveMessage;
import org.nhl.containing.communication.ContainerBean;
import org.nhl.containing.communication.messages.CreateMessage;
import org.nhl.containing.communication.messages.Message;
import org.nhl.containing.communication.messages.SpeedMessage;
import org.nhl.containing.communication.Xml;
import org.nhl.containing.communication.messages.CraneMessage;
import org.nhl.containing.cranes.Crane;
import org.nhl.containing.cranes.DockingCrane;
import org.nhl.containing.cranes.StorageCrane;
import org.nhl.containing.cranes.TrainCrane;
import org.nhl.containing.cranes.TruckCrane;
import org.xml.sax.SAXException;

/**
 * test
 *
 * @author normenhansen
 */
public class Simulation extends SimpleApplication {

    private List<Transporter> transporterPool;
    private List<Transporter> transporters;
    private List<ArriveMessage> arriveMessages;
    private List<CraneMessage> craneMessages;
    private List<Crane> craneList;
    private List<Container> containerList;
    private TrainArea trainArea;
    private LorryArea lorryArea;
    private BoatArea boatArea;
    private BoatArea inlandBoatArea;
    private StorageArea boatStorageArea;
    private StorageArea trainStorageArea;
    private StorageArea lorryStorageArea;
    private List<Agv> agvList;
    private Client client;
    private HUD HUD;
    private boolean debug;
    private Calendar cal;
    private Date currentDate;
    private long lastTime;
    private final static int TIME_MULTIPLIER = 200;
    private Inlandship ship1;
    private Inlandship ship2;
    private float speedMultiplier;
    private float timeMultiplier = 1;

    public Simulation() {
        client = new Client();
        transporterPool = new ArrayList<>();
        craneMessages = new ArrayList<>();
        agvList = new ArrayList<>();
        craneList = new ArrayList<>();
        arriveMessages = new ArrayList<>();
        transporters = new ArrayList<>();
        containerList = new ArrayList<>();
    }

    @Override
    public void simpleInitApp() {
        guiFont = assetManager.loadFont("Interface/Fonts/TimesNewRoman.fnt");
        initCam();
        initUserInput();
        initScene();
        initDate();
        HUD = new HUD(this.guiNode, guiFont);
        Thread clientThread = new Thread(client);
        clientThread.setName("ClientThread");
        clientThread.start();
        try {
            Thread.sleep(1000);
        } catch (Throwable e) {
        }
    }

    @Override
    public void simpleUpdate(float tpf) {
        handleMessages();
        updateDate();
        handleProcessingMessage();
    }

    @Override
    public void simpleRender(RenderManager rm) {
        //TODO: add render code
    }

    @Override
    public void destroy() {
        super.destroy();
        client.stop();
    }

    private void handleMessages() {
        List<String> xmlMessages = new ArrayList<String>();
        while (true) {
            String xmlMessage = client.getMessage();
            if (xmlMessage == null) {
                break;
            }
            xmlMessages.add(xmlMessage);
        }

        for (String xmlMessage : xmlMessages) {
            handleMessage(xmlMessage);
        }
    }

    private void handleMessage(String xmlMessage) {
        Message message = null;
        try {
            message = Xml.parseXmlMessage(xmlMessage);
        } catch (ParserConfigurationException | SAXException | IOException e) {
            e.printStackTrace();
        } catch (IllegalArgumentException e) {
            // Maybe just stacktrace here? Depends on how robust we want this to
            // be. With the current code, it just discards the erroneous
            // message.
            System.out.println(xmlMessage + " is not a valid message");
            return;
        }

        switch (message.getMessageType()) {
            case Message.CREATE:
                handleCreateMessage((CreateMessage) message);
                break;
            case Message.ARRIVE:
                handleArriveMessage((ArriveMessage) message);
                break;
            case Message.SPEED:
                handleSpeedMessage((SpeedMessage) message);
                break;
            case Message.CRANE:
                handleCraneMessage((CraneMessage) message);
                break;
            default:
                throw new IllegalArgumentException(message.getMessageType()
                        + " is not a legal message type");
        }
    }

    private void handleCreateMessage(CreateMessage message) {
        List<Container> containers = new ArrayList<Container>();
        for (ContainerBean containerBean : message.getContainerBeans()) {
            Container container = new Container(assetManager, containerBean.getOwner(),
                    containerBean.getContainerNr(), containerBean.getxLoc(),
                    containerBean.getyLoc(), containerBean.getzLoc());
            containers.add(container);
        }

        switch (message.getTransporterType()) {
            case "vrachtauto":
                Lorry lorry = new Lorry(assetManager,
                        message.getTransporterIdentifier(), containers.get(0));
                transporterPool.add(lorry);
                break;
            case "trein":
                Train train = new Train(assetManager,
                        message.getTransporterIdentifier(), containers);
                transporterPool.add(train);
                break;
            case "binnenschip":
                Inlandship inland = new Inlandship(assetManager,
                        message.getTransporterIdentifier(), containers);
                transporterPool.add(inland);
                break;
            case "zeeschip":
                Seaship sea = new Seaship(assetManager,
                        message.getTransporterIdentifier(), containers);
                transporterPool.add(sea);
                break;
            default:
                throw new IllegalArgumentException(message.getTransporterType()
                        + " is not a legal transporter type");
        }
        containerList.addAll(containers);
        sendOkMessage(message);
    }

    private void handleArriveMessage(ArriveMessage message) {
        boolean exists = false;

        for (Transporter poolTransporter : transporterPool) {
            if (poolTransporter.getId() == message.getTransporterId()) {
                poolTransporter.setProcessingMessageId(message.getId());
                rootNode.attachChild(poolTransporter);
                poolTransporter.multiplySpeed(speedMultiplier);
                poolTransporter.arrive(message.getDepotIndex());
                arriveMessages.add(message);

                exists = true;
                break;
            }
        }
        if (!exists) {
            throw new IllegalArgumentException("Transporter " + message.getTransporterId()
                    + " does not exist");
        }



        // Tell transporter to *arrive*. Rename move() to arrive(). move() is
        // too ambiguous. move() should probably be an *abstract* method within
        // Transporter, to be actually defined within each of the subclasses.
        //
        // Set `processingMessageId` within the Transporter/Vehicle/Object to
        // this message ID. Once the Transporter has arrived at its destination,
        // check for this in a separate function in simpleUpdate(), and then
        // send an OK message for the Transporter/Vehicle/Object's
        // `processingMessageId`.
        //
        // The `processingMesageId` also applies to cranes and AGVs. Cranes,
        // AGVs and transporters should all therefore be subclassed from the
        // same class, OR all implement the same interface (ProcessesMessage).
        // An interface is the cleanest solution, and can be found within the
        // backend. The default value should be -1 (i.e., not processing any
        // messages).
        //
        // Finally, pop transporter from transporterPool, and add it to
        // `transporters`. This list doesn't exist yet, but is trivial to
        // create. The reason we don't want to keep the transporter in the pool,
        // is because we want a way of discerning whether a transporter can
        // actually be interacted with.
    }

    /**
     * Checks the incoming CraneMessage which cranetype we are talking about
     * with which ID and sets the processingMessageId of that crane to the
     * messageID
     *
     * @param message the incoming craneMessage object that is being analyzed
     */
    private void handleCraneMessage(CraneMessage message) {
        DockingCrane dockingCrane = null;
        StorageCrane storageCrane = null;
        TrainCrane trainCrane = null;
        TruckCrane truckCrane = null;

        craneMessages.add(message);

        for (Crane crane : craneList) {
            //Get the crane with the correct type and ID
            if (crane.getName().equals(message.getCraneType()) && crane.getId() == message.getCraneIdentifier()) {

                if (message.getTransporterType().equals("")) {
                    //if there isnt a transportertype then we will always be dealing with a storageCrane
                    storageCrane = (StorageCrane) crane;
                    storageCrane.setProcessingMessageId(message.getId());
                    //TODO:    STORAGE CRANE LOGIC HERE
                    break;
                } else {
                    //if there is a transporter type check which crane we are dealing with
                    switch (message.getCraneType()) {
                        case "DockingCrane":
                            dockingCrane = (DockingCrane) crane;
                            dockingCrane.setProcessingMessageId(message.getId());
                            dockingCrane.boatToAgv(findContainer(message.getContainerNumber()), findAGV(message.getAgvIdentifier()));
                            break;
                        case "TrainCrane":
//                            trainCrane = (TrainCrane) crane;
//                            trainCrane.setProcessingMessageId(message.getId());
//                            trainCrane.trainToAgv(findContainer(message.getContainerNumber()), findAGV(message.getAgvIdentifier()));
                            break;
                        case "TruckCrane":
                            truckCrane = (TruckCrane) crane;
                            truckCrane.setProcessingMessageId(message.getId());
                            truckCrane.truckToAgv(findContainer(message.getContainerNumber()), findAGV(message.getAgvIdentifier()));
                            break;
                        default:
                            throw new IllegalArgumentException(message.getCraneType()
                                    + " is not a legal crane type");
                    }
                }
            }
        }

    }

    /**
     * Tries to find a AGV by the given input
     *
     * @param id An ID of the AGV we are going to search for
     * @return returns null if the agv is not found
     */
    private Agv findAGV(int id) {

        for (Agv AGV : agvList) {
            if (AGV.getId() == id) {
                return AGV;
            }
        }
        return null;
    }

    /**
     * Tries to find a transporter by the given input
     *
     * @param transporterType A transporter type where we are going to search
     * for
     * @param id An ID of the tranposporter we are going to search for
     * @return
     */
    private Transporter findTransporter(String transporterType, int id) {

        for (Transporter transporter : transporters) {
            if (transporter.getId() == id) {
                switch (transporterType) {
                    case "vrachtauto":
                        Lorry lorry = (Lorry) transporter;
                        return lorry;
                    case "trein":
                        Train train = (Train) transporter;
                        return train;
                    case "binnenschip":
                        Inlandship inlandship = (Inlandship) transporter;
                        return inlandship;
                    case "zeeschip":
                        Seaship seaship = (Seaship) transporter;
                        return seaship;
                    default:
                        throw new IllegalArgumentException(transporterType
                                + " is not a legal transporter type");
                }
            }
        }
        return null;
    }

    /**
     * Tries to find a container by the given input
     *
     * @param containerNumber A number of the container we are going to search
     * for
     * @return returns null if nothing is found.
     */
    private Container findContainer(int containerNumber) {

        for (Container container : containerList) {
            if (container.getContainerID() == containerNumber) {
                return container;
            }
        }
        return null;
    }

    private void handleSpeedMessage(SpeedMessage message) {
        speedMultiplier = message.getSpeed();
        timeMultiplier = message.getSpeed();
        changeCraneSpeed();
        sendOkMessage(message);
    }

    /**
     * Checks wether a transporter or a crane is ready for a new job and sends
     * back an OK-message to the backend system.
     */
    private void handleProcessingMessage() {

        //Loop through all transporters and send an OK message when a transporter has arrived to its destination
        Iterator<Transporter> itrTransporter = transporterPool.iterator();
        while (itrTransporter.hasNext()) {
            Transporter poolTransporter = itrTransporter.next();
            if (poolTransporter.isArrived()) {
                Iterator<ArriveMessage> itrMessage = arriveMessages.iterator();
                while (itrMessage.hasNext()) {
                    Message msg = itrMessage.next();
                    if (msg.getId() == poolTransporter.getProcessingMessageId()) {
                        sendOkMessage(msg);
                        transporters.add(poolTransporter);
                        itrTransporter.remove();
                        itrMessage.remove();
                    }
                }

            }

        }

        Iterator<Crane> itrCrane = craneList.iterator();
        while (itrCrane.hasNext()) {
            Crane crane = itrCrane.next();
            if (crane.isArrived()) {
                Iterator<CraneMessage> itrMessage = craneMessages.iterator();
                while (itrMessage.hasNext()) {
                    Message msg = itrMessage.next();
                    if (msg.getId() == crane.getProcessingMessageId()) {
                        sendOkMessage(msg);
                        itrMessage.remove();
                    }
                }
            }
        }


    }

    /**
     * Sends an OK-type message to the controller of the object that is ready
     * for a new task -SUBJECT TO CHANGE, MAYBE SUPERCLASS OBJECT IN THE
     * FUTURE!-
     *
     * @param veh -SUBJECT TO CHANGE, MAYBE SUPERCLASS OBJECT IN THE FUTURE!-
     */
    private void sendOkMessage(Message message) {
        client.writeMessage("<Ok><id>" + message.getId() + "</id></Ok>");
    }

    /**
     * Reads the message object and creates and returns a Transporter from its
     * information.
     *
     * @param message Create message.
     * @return Transporter as described in the message.
     */
    private Transporter createTransporterFromMessage(Message message) {
        return null;
    }

    private void createAGVPath() {
        Agv agv = new Agv(assetManager, -1);
        rootNode.attachChild(agv);
        MotionPath agvPath = new MotionPath();
        MotionEvent agvmotionControl = new MotionEvent(agv, agvPath);
        //Create the AGV waypoints
        //waypoint A
        agvPath.addWayPoint(new Vector3f(580, 0, -140));
        //waypont C
        agvPath.addWayPoint(new Vector3f(330, 0, -140));
        //waypoint E
        agvPath.addWayPoint(new Vector3f(70, 0, -140));
        //waypoint G
        agvPath.addWayPoint(new Vector3f(-210, 0, -140));
        //waypoint H
        agvPath.addWayPoint(new Vector3f(-210, 0, 135));
        //waypoint F
        agvPath.addWayPoint(new Vector3f(70, 0, 135));
        //waypoint D
        agvPath.addWayPoint(new Vector3f(330, 0, 136));
        //waypoint B
        agvPath.addWayPoint(new Vector3f(580, 0, 135));


        agvPath.setCurveTension(0.1f);
        // set the speed and direction of the AGV using motioncontrol
        agvmotionControl.setDirectionType(MotionEvent.Direction.PathAndRotation);
        agvmotionControl.setRotation(new Quaternion().fromAngleNormalAxis(0, Vector3f.UNIT_Y));
        agvmotionControl.setInitialDuration(10f);
        agvmotionControl.setSpeed(1f);
        //make the vehicles start moving
        //agvmotionControl.setLoopMode(LoopMode.Loop);
        agvmotionControl.play();
        //make waypoints visible
        //agvPath.disableDebugShape();
    }

    /**
     * Initialises the simulation date.
     */
    private void initDate() {
        cal = Calendar.getInstance();
        cal.set(Calendar.YEAR, 2004);
        cal.set(Calendar.MONTH, 11);
        cal.set(Calendar.DAY_OF_MONTH, 1);
        cal.set(Calendar.MINUTE, 0);
        cal.set(Calendar.HOUR_OF_DAY, 0);
        cal.set(Calendar.SECOND, 0);
        cal.set(Calendar.MILLISECOND, 0);
        currentDate = cal.getTime();
        lastTime = System.currentTimeMillis();
    }

    private void changeCraneSpeed() {
        List<Crane> dockingCranes = new ArrayList<>();
        dockingCranes.addAll(boatArea.getDockingCranes());
        dockingCranes.addAll(lorryArea.getTruckCranes());
        dockingCranes.addAll(trainArea.getTrainCranes());
        dockingCranes.addAll(boatStorageArea.getStorageCranes());
        dockingCranes.addAll(lorryStorageArea.getStorageCranes());
        dockingCranes.addAll(trainStorageArea.getStorageCranes());
        for (Crane crane : dockingCranes) {
            crane.multiplySpeed(speedMultiplier);
        }
    }

    /**
     * Updates the simulation date.
     * <p/>
     * Compares the time since the last function call to the current time. This
     * is the delta time. The delta time is added to the simulation date,
     * multiplied by the specified TIME_MULTIPLIER.
     */
    private void updateDate() {
        long curTime = System.currentTimeMillis();
        int deltaTime = (int) (curTime - lastTime);
        cal.add(Calendar.MILLISECOND, deltaTime * (int) timeMultiplier);
        currentDate = cal.getTime();
        lastTime = curTime;

        HUD.updateDateText(currentDate);
    }

    /**
     * Camera settings of the scene.
     */
    private void initCam() {
        viewPort.setBackgroundColor(ColorRGBA.Blue);
        cam.setLocation(new Vector3f(-240, 5, 220));
        flyCam.setMoveSpeed(50);
    }

    /*
     * Method for initializing the scene.
     */
    private void initScene() {
        initLighting();
        initSky();
        initAreas();
        initPlatform();
        initAgvLorry();
        initAgvTrain();
        initAgvShip();
    }

    private void initSky() {
        rootNode.attachChild(SkyFactory.createSky(assetManager, "Textures/Skybox/Skybox.dds", false));
    }

    private void initLighting() {
        // Light pointing diagonal from the top right to the bottom left.
        DirectionalLight light = new DirectionalLight();
        light.setDirection((new Vector3f(-0.5f, -0.5f, -0.5f)).normalizeLocal());
        light.setColor(ColorRGBA.White);
        rootNode.addLight(light);

        // A second light pointing diagonal from the bottom left to the top right.
        DirectionalLight secondLight = new DirectionalLight();
        secondLight.setDirection((new Vector3f(0.5f, 0.5f, 0.5f)).normalizeLocal());
        secondLight.setColor(ColorRGBA.White);
        rootNode.addLight(secondLight);
    }

    private void initAreas() {
        // Add lorry area.
        lorryArea = new LorryArea(assetManager, 20);
        lorryArea.setLocalTranslation(300, 0, 170);
        rootNode.attachChild(lorryArea);

        // Add the TrainArea.
        trainArea = new TrainArea(assetManager, 4);
        trainArea.setLocalTranslation(-160, 0, -180);
        rootNode.attachChild(trainArea);

        // Add the BoatArea (Sea).
        boatArea = new BoatArea(assetManager, BoatArea.AreaType.SEASHIP, 10, 28);
        boatArea.setLocalTranslation(-325, 0, -100);
        rootNode.attachChild(boatArea);

        // Add the inlandBoatArea.
        inlandBoatArea = new BoatArea(assetManager, BoatArea.AreaType.INLANDSHIP, 8, 40);
        inlandBoatArea.setLocalTranslation(-240, 0, 220);
        rootNode.attachChild(inlandBoatArea);

        // Add the StorageArea for boat containers.
        boatStorageArea = new StorageArea(assetManager, 4);
        boatStorageArea.setLocalTranslation(-150, 0, -120);
        rootNode.attachChild(boatStorageArea);

        // Add the StorageArea for train containers.
        trainStorageArea = new StorageArea(assetManager, 4);
        trainStorageArea.setLocalTranslation(130, 0, -120);
        rootNode.attachChild(trainStorageArea);

        // Add the StorageArea for lorry containers.
        lorryStorageArea = new StorageArea(assetManager, 4);
        lorryStorageArea.setLocalTranslation(380, 0, -120);
        rootNode.attachChild(lorryStorageArea);

        //Add all the cranes to a list for easier accesability
        craneList.addAll(lorryArea.getTruckCranes());
        craneList.addAll(trainArea.getTrainCranes());
        craneList.addAll(boatArea.getDockingCranes());
        craneList.addAll(inlandBoatArea.getDockingCranes());
        craneList.addAll(boatStorageArea.getStorageCranes());
        craneList.addAll(trainStorageArea.getStorageCranes());
        craneList.addAll(lorryStorageArea.getStorageCranes());
    }

    private void initPlatform() {
        // Platform for the scene.
        Spatial platform = assetManager.loadModel("Models/platform/platform.j3o");
        //vergroot platform
        platform.scale(30, 45, 20);
        //schuif platform op
        platform.setLocalTranslation(150, -9, 0);
        rootNode.attachChild(platform);
        //create water
        //water
        Box waterplatform = new Box(1000f, 1f, 1000f);
        Geometry waterGeo = new Geometry("", waterplatform);
        Material boxMat = new Material(assetManager, "Common/MatDefs/Misc/Unshaded.j3md");
        Texture waterTexture = assetManager.loadTexture("/Models/platform/water.jpg");
        boxMat.setTexture("ColorMap", waterTexture);
        waterGeo.setLocalTranslation(150, -17, 0);
        waterGeo.setMaterial(boxMat);
        rootNode.attachChild(waterGeo);
    }

    private void initUserInput() {
        inputManager.addMapping("debugmode", new KeyTrigger(KeyInput.KEY_P));
        ActionListener acl = new ActionListener() {
            public void onAction(String name, boolean keyPressed, float tpf) {
                if (name.equals("debugmode") && keyPressed) {
                    if (!debug) {
                        debug = !debug;
//                        Inlandship test = new Inlandship(assetManager, 0, new ArrayList());
//                        rootNode.attachChild(test);
//                        test.arrive(0);
//                        Seaship test2 = new Seaship(assetManager, 0, new ArrayList());
//                        rootNode.attachChild(test2);
//                        test2.arrive(0);
//                        Train traintest = new Train(assetManager, 0, new ArrayList());
//                        rootNode.attachChild(traintest);
//                        traintest.arrive(0);
                        ship1 = new Inlandship(assetManager, 0, new ArrayList());
                        ship2 = new Inlandship(assetManager, 0, new ArrayList());
                        rootNode.attachChild(ship1);
                        rootNode.attachChild(ship2);
                        ship1.arrive(0);
                        Agv agvtest = new Agv(assetManager, 0);
                        rootNode.attachChild(agvtest);
                        char[] testarr = {'A', 'B', 'D', 'C', 'E', 'F', 'H', 'G'};
                        agvtest.move(testarr);

                        ship2.arrive(1);
                        debug = false;
                        Inlandship test = new Inlandship(assetManager, 0, new ArrayList());
                        rootNode.attachChild(test);
                        test.multiplySpeed(speedMultiplier);
                        test.arrive(0);
                        Seaship test2 = new Seaship(assetManager, 0, new ArrayList());
                        rootNode.attachChild(test2);
                        test2.multiplySpeed(speedMultiplier);
                        test2.arrive(0);
                        Train traintest = new Train(assetManager, 0, new ArrayList());
                        rootNode.attachChild(traintest);
                        traintest.multiplySpeed(speedMultiplier);
                        traintest.arrive(0);
                        //testing train code
                        createAGVPath();
                    } else {
                        //System.out.println(ship1.getLocalTranslation());
                        debug = !debug;
                        ship1.depart();
                        ship2.depart();
                    }
                }
            }
        };
        inputManager.addListener(acl, "debugmode");
    }
    /*
     * Spawn avg on ship storage deck
     */

    private void initAgvShip() {
        int agvStartPoint = -167;
        for (int i = 1; i < 29; i++) {
            if (i % 7 != 0) {
                Agv agv = new Agv(assetManager, i);
                agv.setLocalTranslation(agvStartPoint + (4.7f * i), 0, -122);
                rootNode.attachChild(agv);
                agvList.add(agv);
            } else {
                agvStartPoint += 17;
            }
        }
    }
    /*
     * Spawn avg on train storage deck
     */

    private void initAgvTrain() {
        int agvStartPoint = -18;
        for (int i = 29; i < 57; i++) {
            if (i % 7 != 0) {
                Agv agv = new Agv(assetManager, i);
                agv.setLocalTranslation(agvStartPoint + (4.7f * i), 0, -122);
                rootNode.attachChild(agv);
                agvList.add(agv);
            } else {
                agvStartPoint += 17;
            }
        }
    }
    /*
     * Spawn avg on lorry storage deck
     */

    private void initAgvLorry() {
        int agvStartPoint = 100;
        for (int i = 57; i < 85; i++) {
            if (i % 7 != 0) {
                Agv agv = new Agv(assetManager, i);
                agv.setLocalTranslation(agvStartPoint + (4.7f * i), 0, -122);
                rootNode.attachChild(agv);
                agvList.add(agv);
            } else {
                agvStartPoint += 17;
            }
        }
    }

}
