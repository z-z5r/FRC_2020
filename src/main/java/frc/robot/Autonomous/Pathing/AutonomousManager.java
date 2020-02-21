package frc.robot.Autonomous.Pathing;

import java.sql.Time;

import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj2.command.Command;
import frc.robot.Autonomous.Pathing.Commands.AlignShoot;
import frc.robot.Autonomous.Pathing.Enums.AutoPaths;
import frc.robot.Hardware.Sensors.NavX;
import frc.robot.Subsystems.ArcShooter;
import frc.robot.Subsystems.BallSystem;
import frc.robot.Subsystems.DriveTrainSystem;
import frc.robot.Utilities.Control.LimelightAlignment;
import frc.robot.Utilities.Control.PID;

/**
 * The Autonomous Manager Class Handles All Control Of The Robot During The Autonomous Period
 * Each Auto Routine separated into classes
 * 
 * @author Will Richards
 */
public class AutonomousManager{

    // Vision

    // Class used to handle alignment from the limelight
    private LimelightAlignment alignment;

    // Whether or not the limelight is currently set to track a target
    private boolean limelightTracking = false;

    // Whether or not the robot has finished aligning with the target
    private boolean robotAligned = false;

    // Whether or not we are actively running the shooter
    private boolean runningShooter = false;

    //Pathing

    private Pathing pathing;

    //Subsystems
    
    private BallSystem ballSystem;
    private DriveTrainSystem drive;
    private ArcShooter shooter;

    //Auto Paths
    private EightBallOne basicEight;

    // The path the robot will run
    private AutoPaths path;



    /**
     * Initialize all required classes locally 
     * @param alignment limelight control loop
     * @param pathing RAMSETE command loop
     * @param ballSystem ball storage / indexing system
     * @param drive drive train system
     * @param shooter shooter system
     */
    public AutonomousManager(LimelightAlignment alignment, Pathing pathing, BallSystem ballSystem, DriveTrainSystem drive, ArcShooter shooter){

        // Init all variables
        this.alignment = alignment;
        this.pathing = pathing;
        this.ballSystem = ballSystem;
        this.drive = drive;
        this.shooter = shooter;

        // Create the first auto path
        basicEight = new EightBallOne();
    }

    /**
     * Returns a reference to the basic eight ball auto path
     * @return 8 ball auto path
     */
    public EightBallOne getBasicEight(){
        return basicEight;
    }

    /**
     * Set the robot path to run
     * @param path enum of the type
     */
    public void setPath(AutoPaths path){
        this.path = path;
    }

    /**
     * Overall setup that will run whatever the selected path is 
     */
    public void setup(){
        switch(path){
            case BASIC_EIGHT:
                getBasicEight().setup();
                break;
        }
    }

    /**
     * Overall periodic method that will run the selected auto paths periodic method
     */
    public void periodic(){
        switch(path){
            case BASIC_EIGHT:
                getBasicEight().periodic();
                break;
        }
    }
   

    /**
     * First simple auto path for eight balls
     */
    public class EightBallOne{

        private int alignLoop = 0;
        private PID pid;

        //Change later
        private boolean hasShotBalls = true;
        private boolean hasStartedSecondPath = false;
        private boolean turned180 = false;
        private boolean firstTurnPass = true;
        private boolean shouldTurn = false;
        private boolean stage3 = false;

        private int turnLoopCount = 0;

        private Command alignCommand;

        /**
         * Called in the autonomous init function to setup the required parts of the routine
         */
        public void setup(){

            //Create a new align command, and tell it what to run after that command
            alignCommand = new AlignShoot(alignment, shooter, ballSystem);
            //alignCommand.andThen(next)

            // Turn off tracking
            limelightTracking = false;

            // Reset all characteristics of the robot on init
            pathing.resetProperties();

            // Robot is no longer aligned
            robotAligned = false;

            // Shooter is no longer running
            runningShooter = false;

            hasShotBalls = true;
            hasStartedSecondPath = false;
            turned180 = false;
            firstTurnPass = true;
            shouldTurn = false;

            turnLoopCount = 0;
            

            // Stop the indexer
            ballSystem.getIndexer().stopIndexing();

            // Stop the shooter
            shooter.stopShooter();

            // Starts the first section of the path and tells the robot to start tracking the target when complete
            pathing.runPath(PathContainer.basicEightPartOne(), () -> nextStage(()->setTracking(true)));
            
            //Test first
            //pathing.runPath(PathContainer.basicEightPartOne(), () -> nextStage(()->alignCommand.schedule()));

            // Turning Constant
            pid = new PID(.016,0,0.01);
            pid.setAcceptableRange(0.25);
            pid.setMaxOutput(0.2);
           
        }

        /**
         * Called during the jautonomous periodic method to allow for active control 
         */
        public void periodic(){

            //// Initial tracking statement
            if(getTrackingStatus()){
                alignShoot();
                System.out.println("Align");

            }
            else if(!getTrackingStatus() && shouldTurn && !turned180){
                turn180();
                System.out.println("Turn");
            }
            else if(!hasStartedSecondPath && !getTrackingStatus() && turned180){
                ballSystem.getIntake().extendIntake();
                ballSystem.getIntake().runFrontIntakeForward();

                
                //Get the actual yaw value
                pathing.resetProperties();
                pathing.runPath(PathContainer.turnAndPickUp(), () -> nextStage(() -> retractAndStopIntake()));
                hasStartedSecondPath = true;
            }
            else if(stage3){
                pathing.runPath(PathContainer.driveBackToStart());
            }

            subsystemUpdater();
        }

        private void retractAndStopIntake(){
            ballSystem.getIntake().stopFrontIntake();
            ballSystem.getIntake().retractIntake();
            stage3 = true;
        }

        /**
         * Flip the robot 180 degrees
         */
        private boolean turn180(){

            // If its the first time turning
            if(firstTurnPass){
                pid.setSetpoint(180);
                drive.enableOpenRampRate(1);
                firstTurnPass = false;
                
            }
            else{
            
                double power = pid.calcOutput(NavX.get().getAngle());

                // Check if the robots output power is less than 0.26 motor power if so apply an additional power of 0.3 ontop of the current power
                if(Math.abs(power) < 0.26){
                    power += Math.copySign(0.3, power);
                }

                if(pid.isInRange()){
                    drive.arcadeDrive(0, 0);
                    drive.enableOpenRampRate(0);
                    turned180 = true;
                    if(turnLoopCount == 5)
                        return true;
                    else
                        turnLoopCount++;
                }
                else{
                    drive.arcadeDrive(power, 0);
                    return false;
                }
            }
            return false;
        }

        /**
         * Allows the robot to align and shoot balls into the goal
         */
        private void alignShoot(){

            // Order is important so that the control loop doesn't run if the robot is already aligned
            if(!robotAligned && alignment.controlLoop()){
                if(alignLoop == 10){
                    robotAligned = true;
                    shouldTurn = true;
                    
                }
                else
                    alignLoop++;
            }

            // // Check if the robot is already running the shooter, if not start it
            else if(!runningShooter){
                shooter.enableShooter();
                System.out.println(shooter.getStatus());
                if(shooter.getStatus()){
                    shooter.stopShooter();
                    runningShooter = false;
                    
                    setTracking(false);
                }
                
            }

            // Finally check if the robot is aligned at the shooter is at "Full speed", if so start the belts
        //     else if(robotAligned == true && shooter.isFull() && !hasShotBalls){
        //         ballSystem.getIndexer().standardIndex();
        //        alignLoop = 0;
        //     }

        //     // Turn 180 if not already
        //    else if(!hasStartedSecondPath && turn180() && !turned180){
        //         turned180 = true;
        //     }

            


            // Update the shooter toggle
            
        }
    }

     /**
     * Set whether or not the limelight should be trying to track something
     * @param value the tracking value
     */
    private void setTracking(boolean value){
        limelightTracking = value;
    }

    /**
     * Get whether or not the limelight is set to be tracking the target
     * @return the tracking status as a boolean
     */
    private boolean getTrackingStatus(){
        return limelightTracking;
    }

    /**
     * Used to update the statues of subsystems so they have constant feedback
     */
    private void subsystemUpdater(){
        shooter.runShooter();
    }

    /**
     * Is Called at the end of an auto path and it stops the robot and calls the next function in the path
     * @param nextFuntion the function to call
     */
    private void nextStage(Runnable nextFuntion){

        //Stop the robot
        drive.arcadeDrive(0, 0);

        // Run the next function
        nextFuntion.run();
    }

}