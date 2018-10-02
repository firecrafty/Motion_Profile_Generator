package com.mammen.main;

import com.mammen.util.Mathf;

import javafx.beans.binding.ObjectBinding;
import javafx.beans.property.*;
import javafx.collections.FXCollections;

import jaci.pathfinder.Pathfinder;
import jaci.pathfinder.Trajectory;
import jaci.pathfinder.Trajectory.Config;
import jaci.pathfinder.Trajectory.Segment;
import jaci.pathfinder.Waypoint;
import jaci.pathfinder.modifiers.SwerveModifier;
import jaci.pathfinder.modifiers.TankModifier;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.w3c.dom.Text;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSOutput;
import org.w3c.dom.ls.LSSerializer;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;

import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import java.io.*;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;


/******************************************************************************
*   ProfileGenerator
*       This is the model for the program. It contains all the data needed to
*       generate a trajectory as well as the trajectories themselves. We use
*       the JavaBeans model of properties. The trajectories are setup so that
*       they are recalculated whenever any of their dependencies changes value.
*       These dependencies (velocity, accel, jerk, ect. ) are bound to gui
*       elements in their respective fxml controllers.
******************************************************************************/
public class ProfileGenerator 
{
	public static final String PROJECT_EXTENSION = "xml";

	// Types of drive bases
	public enum DriveBase
    {
        TANK( "TANK", "Tank" ),
        SWERVE( "SWERVE", "Swerve" );
        
        private String label;
        private String internalLabel;

		DriveBase( String internalLabel, String label )
		{
		    this.internalLabel = internalLabel;
            this.label = label;
        }

        public String toString()
        {
            return label;
        }

        public String getInternalLabel()
        {
            return internalLabel;
        }
    }

    // Units of every value
    public enum Units
    {
        FEET( "FEET", "Feet" ),
        INCHES( "INCHES", "Inches" ),
        METERS( "METERS", "Meter" );

        private String label;
        private String internalLabel;

        Units( String internalLabel, String label )
        {
            this.internalLabel = internalLabel;
            this.label = label;
        }

        public String toString()
        {
            return label;
        }

        public String getInternalLabel()
        {
            return internalLabel;
        }
    }

    // This enum mirrors jaci.pathfinder.Trajectory.FitMethod for use internally.
    public enum FitMethod
    {
        HERMITE_CUBIC( "HERMITE_CUBIC", "Cubic", Trajectory.FitMethod.HERMITE_CUBIC ),
        HERMITE_QUINTIC( "HERMITE_QUINTIC", "Quintic", Trajectory.FitMethod.HERMITE_QUINTIC );


        private String label;
        private String internalLabel;
        private jaci.pathfinder.Trajectory.FitMethod pf_fitMethod;

        FitMethod( String internalLabel, String label, Trajectory.FitMethod fitMethod )
        {
            this.internalLabel = internalLabel;
            this.label = label;
            this.pf_fitMethod = fitMethod;
        }

        public String toString()
        {
            return label;
        }

        public String getInternalLabel()
        {
            return internalLabel;
        }

        public Trajectory.FitMethod getPfFitMethod()
        {
            return pf_fitMethod;
        }
    }

    public enum ProfileElements
    {
        DELTA_TIME( "DELTA_TIME", "Delta Time", 1 ),
        X_POINT( "X_POINT", "X Point", 2 ),
        Y_POINT( "Y_POINT", "Y Point", 3 ),
        POSITION( "POSITION", "Position", 4 ),
        VELOCITY( "VELOCITY", "Velocity", 5 ),
        ACCELERATION( "ACCELERATION", "Acceleration", 6 ),
        JERK( "JERK", "Jerk", 7 ),
        HEADING( "HEADING", "Heading", 8 ),
        NULL(null, null, 0);

        private String internalLabel;
        private String label;
        private int index;

        ProfileElements( String internalLabel, String label, int index )
        {
            this.internalLabel = internalLabel;
            this.label = label;
            this.index = index;
        }

        public int getIndex() {return index;}

        public String toString()
        {
            return label;
        }

        public String getInternalLabel()
        {
            return internalLabel;
        }
    }

    /******************************************************
    *   Property variables
    ******************************************************/
    private DoubleProperty timeStep;
    private DoubleProperty velocity;
    private DoubleProperty accel;
    private DoubleProperty jerk;
    private DoubleProperty wheelBaseW;
    private DoubleProperty wheelBaseD;
    private Property<DriveBase> driveBase;
    private Property<FitMethod> fitMethod;
    private Property<Units> units;
    
    private ListProperty<Waypoint> waypointList;

    /******************************************************
    *   Trajectories
    ******************************************************/
    private ObjectBinding<Trajectory> source;
    private ObjectBinding<Trajectory> fl;
    private ObjectBinding<Trajectory> fr;
    private ObjectBinding<Trajectory> bl;
    private ObjectBinding<Trajectory> br;

    
    // File stuff
    private DocumentBuilderFactory dbFactory;
    private File workingProject;

    /******************************************************
    *   Constructor
    ******************************************************/
    public ProfileGenerator()
    {
        // Setup properties
        timeStep    = new SimpleDoubleProperty();
        velocity    = new SimpleDoubleProperty();
        accel       = new SimpleDoubleProperty();
        jerk        = new SimpleDoubleProperty();
        wheelBaseD  = new SimpleDoubleProperty();
        wheelBaseW  = new SimpleDoubleProperty();
        driveBase   = new SimpleObjectProperty<>();
        fitMethod   = new SimpleObjectProperty<>();
        units       = new SimpleObjectProperty<>();

        waypointList = new SimpleListProperty<>( FXCollections.observableArrayList() );

    	dbFactory = DocumentBuilderFactory.newInstance();

    	// Initialize to default values.
    	setDefaultValues( Units.FEET );

    	// Convert variables on units change
    	units.addListener( ( o, oldValue, newValue ) -> updateVarUnits( oldValue, newValue ) );

    	// Bind to motion vars and waypoints
        source = new ObjectBinding<Trajectory>()
        {
            {
                // sourceBind depends on these properties;
                // When any of them change, sourceBind's value is recomputed using computeValue() auto-magically;
                super.bind( waypointList, fitMethod, timeStep, velocity, accel, jerk );
            }

            @Override
            protected Trajectory computeValue()
            {
                Config config = new Config( fitMethod.getValue().getPfFitMethod(), Config.SAMPLES_HIGH, timeStep.get(), velocity.get(), accel.get(), jerk.get() );

                // We need at least 2 points to generate a trajectory.
                if( getNumWaypoints() > 1 )
                {
                    try
                    {
                        return Pathfinder.generate( waypointList.toArray( new Waypoint[1] ), config );
                    }
                    catch(Exception e )
                    {
                        return null;
                    }
                }
                else
                {
                    return null;
                }
            }
        };

        fl = new ObjectBinding<Trajectory>()
        {
            {
                super.bind( source, driveBase, wheelBaseW, wheelBaseD );
            }

            @Override
            protected Trajectory computeValue()
            {
                if( source.get() == null )
                    return null;

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    SwerveModifier swerve = new SwerveModifier( source.get() );
                    swerve.modify( wheelBaseW.get(), wheelBaseD.get(), SwerveModifier.Mode.SWERVE_DEFAULT );

                    return swerve.getFrontLeftTrajectory();
                }
                else  // By default, treat everything as tank drive.
                {
                    TankModifier tank = new TankModifier( source.get() );
                    tank.modify( wheelBaseW.get() );

                    return tank.getLeftTrajectory();
                }
            }
        };

        fr = new ObjectBinding<Trajectory>()
        {
            {
                super.bind( source, driveBase, wheelBaseW, wheelBaseD );
            }

            @Override
            protected Trajectory computeValue()
            {
                if( source.get() == null )
                    return null;

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    SwerveModifier swerve = new SwerveModifier( source.get() );

                    swerve.modify( wheelBaseW.get(), wheelBaseD.get(), SwerveModifier.Mode.SWERVE_DEFAULT );

                    return swerve.getFrontRightTrajectory();
                }
                else  // By default, treat everything as tank drive.
                {
                    TankModifier tank = new TankModifier( source.get() );
                    tank.modify( wheelBaseW.get() );

                    return tank.getRightTrajectory();
                }
            }
        };

        bl = new ObjectBinding<Trajectory>()
        {
            {
                super.bind( source, driveBase, wheelBaseW, wheelBaseD );
            }

            @Override
            protected Trajectory computeValue()
            {
                if( source.get() == null )
                    return null;

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    SwerveModifier swerve = new SwerveModifier( source.get() );

                    swerve.modify( wheelBaseW.get(), wheelBaseD.get(), SwerveModifier.Mode.SWERVE_DEFAULT );

                    return swerve.getBackLeftTrajectory();
                }
                else  // By default, treat everything as tank drive.
                {
                    return null;
                }
            }
        };

        br = new ObjectBinding<Trajectory>()
        {
            {
                super.bind( source, driveBase, wheelBaseW, wheelBaseD );
            }

            @Override
            protected Trajectory computeValue()
            {
                if( source.get() == null )
                    return null;

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    SwerveModifier swerve = new SwerveModifier( source.get() );

                    swerve.modify( wheelBaseW.get(), wheelBaseD.get(), SwerveModifier.Mode.SWERVE_DEFAULT );

                    return swerve.getBackRightTrajectory();
                }
                else  // By default, treat everything as tank drive.
                {
                    return null;
                }
            }
        };
    }   /* ProfileGenerator() */


    /**************************************************************************
    *  updateVarUnits
    *      Converts the variables from one Unit to another.
    *
    * @param old_unit The current Unit.
    * @param new_unit The Unit to convert to.
    **************************************************************************/
    private void updateVarUnits( Units old_unit, Units new_unit )
    {
        // TODO: Find a better way of doing this!!!
        //          Maybe storing the values in the backend in feet
        //          and only convert it for display.

    	// Convert each point in the waypoints list
        for( Waypoint wp : waypointList )
        {
            double tmp_x = 0, tmp_y = 0;

            // convert to intermediate unit of feet
            switch( old_unit )
            {
                case FEET:
                    tmp_x = wp.x;
                    tmp_y = wp.y;
                    break;

                case INCHES:
                    tmp_x = Mathf.inchesToFeet( wp.x );
                    tmp_y = Mathf.inchesToFeet( wp.y );
                    break;

                case METERS:
                    tmp_x = Mathf.meterToFeet( wp.x );
                    tmp_y = Mathf.meterToFeet( wp.y );
                    break;
            }

            // convert from intermediate unit of feet
            switch( new_unit )
            {
                case FEET:
                    wp.x = tmp_x;
                    wp.y = tmp_y;
                    break;

                case INCHES:
                    wp.x = Mathf.feetToInches(tmp_x);
                    wp.y = Mathf.feetToInches(tmp_y);
                    break;

                case METERS:
                    wp.x = Mathf.feetToMeter(tmp_x);
                    wp.y = Mathf.feetToMeter(tmp_y);
                    break;
            }

            wp.x = Mathf.round( wp.x, 4 );
            wp.y = Mathf.round( wp.y, 4 );
        }

        // Convert each MP variable to the new unit
    	double tmp_WBW = 0, tmp_vel = 0, tmp_acc = 0, tmp_jer = 0;

    	// convert to intermediate unit of feet
    	switch( old_unit )
    	{
    	case FEET:
    		tmp_WBW = wheelBaseW.get();
    		tmp_vel = velocity.get();
    		tmp_acc = accel.get();
    		tmp_jer = jerk.get();
    		break;
    		
    	case INCHES:
    		tmp_WBW = Mathf.inchesToFeet( wheelBaseW.get() );
    		tmp_vel = Mathf.inchesToFeet( velocity.get() );
    		tmp_acc = Mathf.inchesToFeet( accel.get() );
    		tmp_jer = Mathf.inchesToFeet( jerk.get() );
    		break;
    		
    	case METERS:
    		tmp_WBW = Mathf.meterToFeet( wheelBaseW.get() );
    		tmp_vel = Mathf.meterToFeet( velocity.get() );
    		tmp_acc = Mathf.meterToFeet( accel.get() );
    		tmp_jer = Mathf.meterToFeet( jerk.get() );
    		break;
    	}
    	
    	// convert from intermediate unit of feet
    	switch( new_unit )
    	{
    	case FEET:
    		wheelBaseW.set( tmp_WBW );
    		velocity.set( tmp_vel );
    		accel.set( tmp_acc );
    		jerk.set( tmp_jer );
    		break;
    		
    	case INCHES:
    		wheelBaseW.set( Mathf.feetToInches( tmp_WBW ) );
    		velocity.set( Mathf.feetToInches( tmp_vel ) );
    		accel.set( Mathf.feetToInches( tmp_acc ) );
    		jerk.set( Mathf.feetToInches( tmp_jer ) );
    		
    		break;
    		
    	case METERS:
    		wheelBaseW.set( Mathf.feetToMeter( tmp_WBW ) );
    		velocity.set( Mathf.feetToMeter( tmp_vel ) );
    		accel.set( Mathf.feetToMeter( tmp_acc ) );
    		jerk.set( Mathf.feetToMeter( tmp_jer ) );
    		break;
    	}
    	
    	wheelBaseW  .set( Mathf.round( wheelBaseW.get(),4 ) );
    	velocity    .set( Mathf.round( velocity.get(),  4 ) );
    	accel       .set( Mathf.round( accel.get(),     4 ) );
    	jerk        .set( Mathf.round( jerk.get(),      4 ) );
    }   /* updateVarUnits() */


    /**************************************************************************
     *  exportTrajectoriesJaci
     *      Exports all trajectories to the parent folder, with the given root
     *      name and file extension.
     *
     * @param parentPath Path to the directory to export to.
     * @param ext The file type to export to.
     *************************************************************************/
    public void exportTrajectoriesJaci( File parentPath, String ext ) throws IllegalArgumentException
    {
        File dir = parentPath.getParentFile();

        if( dir != null && !dir.exists() && dir.isDirectory() )
        {
            if( !dir.mkdirs() )
                return;
        }

        switch( ext )
        {
            case ".csv":
                Pathfinder.writeToCSV( new File(parentPath + "_source_Jaci.csv"), source.get() );

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    Pathfinder.writeToCSV(new File(parentPath + "_fl_Jaci.csv"), fl.get() );
                    Pathfinder.writeToCSV(new File(parentPath + "_fr_Jaci.csv"), fr.get() );
                    Pathfinder.writeToCSV(new File(parentPath + "_bl_Jaci.csv"), bl.get() );
                    Pathfinder.writeToCSV(new File(parentPath + "_br_Jaci.csv"), br.get() );
                }
                else
                {
                    Pathfinder.writeToCSV(new File(parentPath + "_left_Jaci.csv"), fl.get() );
                    Pathfinder.writeToCSV(new File(parentPath + "_right_Jaci.csv"), fr.get() );
                }
                break;

            case ".traj":
                Pathfinder.writeToFile(new File(parentPath + "_source_Jaci.traj"), source.get() );

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    Pathfinder.writeToFile(new File(parentPath + "_fl_Jaci.traj"), fl.get() );
                    Pathfinder.writeToFile(new File(parentPath + "_fr_Jaci.traj"), fr.get() );
                    Pathfinder.writeToFile(new File(parentPath + "_bl_Jaci.traj"), bl.get() );
                    Pathfinder.writeToFile(new File(parentPath + "_br_Jaci.traj"), br.get() );
                }
                else
                {
                    Pathfinder.writeToFile(new File(parentPath + "_left_Jaci.traj"), fl.get() );
                    Pathfinder.writeToFile(new File(parentPath + "_right_Jaci.traj"), fr.get() );
                }
                break;

            default:
                throw new IllegalArgumentException( "Invalid file extension" );
        }
    }   /* exportTrajectoriesJaci() */


    /**************************************************************************
    *  exportTrajectoriesTalon
    *       Exports all trajectories to the parent folder, with the given root
    *       name and file extension.
    *
    * @param parentPath Path to the directory to export to.
    * @param ext The file type to export to.
    * @throws IOException
    * @throws IllegalArgumentException
    **************************************************************************/
    public void exportTrajectoriesTalon( File parentPath, String ext ) throws IOException, IllegalArgumentException
    {
        File dir = parentPath.getParentFile();

        if( dir != null && !dir.exists() && dir.isDirectory() )
        {
            if( !dir.mkdirs() )
                return;
        }
        switch( ext ) {
            case ".csv":
                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                	File flFile = new File(parentPath + "_fl_Talon.csv");
			        File frFile = new File(parentPath + "_fr_Talon.csv");
			        File blFile = new File(parentPath + "_bl_Talon.csv");
			        File brFile = new File(parentPath + "_br_Talon.csv");
			        FileWriter flfw = new FileWriter( flFile );
					FileWriter frfw = new FileWriter( frFile );
					FileWriter blfw = new FileWriter( blFile );
					FileWriter brfw = new FileWriter( brFile );
					PrintWriter flpw = new PrintWriter( flfw );
					PrintWriter frpw = new PrintWriter( frfw );
					PrintWriter blpw = new PrintWriter( blfw );
					PrintWriter brpw = new PrintWriter( brfw );
                	// CSV with position and velocity. To be used with Talon SRX Motion
		    		// save front left path to CSV
			    	for( int i = 0; i < fl.get().length(); i++ )
			    	{			
			    		Segment seg = fl.get().get( i );
			    		flpw.printf( "%f, %f, %d\n", seg.position, seg.velocity, (int)(seg.dt * 1000) );
			    	}
			    			
			    	// save front right path to CSV
			    	for( int i = 0; i < fr.get().length(); i++ )
			    	{			
			    		Segment seg = fr.get().get( i );
			    		frpw.printf( "%f, %f, %d\n", seg.position, seg.velocity, (int)(seg.dt * 1000) );
			    	}
			    	
			    	// save back left path to CSV
			    	for( int i = 0; i < bl.get().length(); i++ )
			    	{			
			    		Segment seg = bl.get().get( i );
			    		blpw.printf( "%f, %f, %d\n", seg.position, seg.velocity, (int)(seg.dt * 1000) );
			    	}
			    			
			    	// save back right path to CSV
			    	for( int i = 0; i < br.get().length(); i++ )
			    	{			
			    		Segment seg = br.get().get( i );
			    		brpw.printf( "%f, %f, %d\n", seg.position, seg.velocity, (int)(seg.dt * 1000) );
			    	}
			    	flpw.close();
			    	frpw.close();
			    	blpw.close();
			    	brpw.close();
                }
                else
                    {
                	File lFile = new File(parentPath + "_left_Talon.csv");
			        File rFile = new File(parentPath + "_right_Talon.csv");
			        FileWriter lfw = new FileWriter( lFile );
					FileWriter rfw = new FileWriter( rFile );
					PrintWriter lpw = new PrintWriter( lfw );
					PrintWriter rpw = new PrintWriter( rfw );
                	// CSV with position and velocity. To be used with Talon SRX Motion
			    	// save left path to CSV
			    	for( int i = 0; i < fl.get().length(); i++ )
			    	{			
			    		Segment seg = fl.get().get( i );
			    		lpw.printf("%f, %f, %d\n", seg.position, seg.velocity, (int)( seg.dt * 1000 ) );
			    	}
			    			
			    	// save right path to CSV
			    	for( int i = 0; i < fr.get().length(); i++ )
			    	{			
			    		Segment seg = fr.get().get( i );
			    		rpw.printf("%f, %f, %d\n", seg.position, seg.velocity, (int)( seg.dt * 1000 ) );
			    	}

			    	lpw.close();
			    	rpw.close();
                }
                break;

            case ".traj":
                Pathfinder.writeToFile( new File(parentPath + "_source_detailed.traj"), source.get() );

                if( driveBase.getValue() == DriveBase.SWERVE )
                {
                    Pathfinder.writeToFile( new File(parentPath + "_fl_detailed.traj"), fl.get() );
                    Pathfinder.writeToFile( new File(parentPath + "_fr_detailed.traj"), fr.get() );
                    Pathfinder.writeToFile( new File(parentPath + "_bl_detailed.traj"), bl.get() );
                    Pathfinder.writeToFile( new File(parentPath + "_br_detailed.traj"), br.get() );
                }
                else
                {
                    Pathfinder.writeToFile( new File(parentPath + "_left_detailed.traj"), fl.get() );
                    Pathfinder.writeToFile( new File(parentPath + "_right_detailed.traj"), fr.get() );
                }
                break;

            default:
                throw new IllegalArgumentException("Invalid file extension");
        }
    }   /* exportTrajectoriesTalon() */


    /**
     * Saves the project in XML format.
     */
    public void saveProjectAs( File path ) throws IOException, ParserConfigurationException
    {
        if( !path.getAbsolutePath().endsWith("." + PROJECT_EXTENSION) )
            path = new File(path + "." + PROJECT_EXTENSION);

        File dir = path.getParentFile();

        if( dir != null && !dir.exists() && dir.isDirectory() )
        {
            if (!dir.mkdirs())
                return;
        }

        if( path.exists() && !path.delete() )
            return;

        workingProject = path;

        saveWorkingProject();
    }

    /**
     * Saves the working project.
     */
    public void saveWorkingProject() throws IOException, ParserConfigurationException
    {
        if( workingProject != null )
        {
            // Create document
            DocumentBuilder db = dbFactory.newDocumentBuilder();
            Document dom = db.newDocument();

            Element trajectoryEle = dom.createElement("Trajectory" );

            trajectoryEle.setAttribute("dt", "" + timeStep );
            trajectoryEle.setAttribute("velocity", "" + velocity );
            trajectoryEle.setAttribute("acceleration", "" + accel );
            trajectoryEle.setAttribute("jerk", "" + jerk );
            trajectoryEle.setAttribute("wheelBaseW", "" + wheelBaseW );
            trajectoryEle.setAttribute("wheelBaseD", "" + wheelBaseD );
            trajectoryEle.setAttribute("fitMethod", "" + fitMethod.getValue().getInternalLabel() );
            trajectoryEle.setAttribute("driveBase", "" + driveBase.getValue().getInternalLabel() );
            trajectoryEle.setAttribute("units", "" + units.getValue().getInternalLabel() );

            dom.appendChild( trajectoryEle );

            for( Waypoint w : waypointList )
            {
                Element waypointEle = dom.createElement("Waypoint" );
                Element xEle = dom.createElement("X" );
                Element yEle = dom.createElement("Y" );
                Element angleEle = dom.createElement("Angle" );
                Text xText = dom.createTextNode("" + w.x );
                Text yText = dom.createTextNode("" + w.y );
                Text angleText = dom.createTextNode("" + w.angle );

                xEle.appendChild( xText );
                yEle.appendChild( yText );
                angleEle.appendChild( angleText );

                waypointEle.appendChild( xEle );
                waypointEle.appendChild( yEle );
                waypointEle.appendChild( angleEle );

                trajectoryEle.appendChild( waypointEle );
            }

            FileOutputStream fos = null;
            try
            {
                fos = new FileOutputStream( workingProject );
                DOMImplementationRegistry reg = DOMImplementationRegistry.newInstance();
                DOMImplementationLS impl = (DOMImplementationLS) reg.getDOMImplementation("LS" );
                LSSerializer serializer = impl.createLSSerializer();
                
                serializer.getDomConfig().setParameter("format-pretty-print", Boolean.TRUE );
                
                LSOutput lso = impl.createLSOutput();
                lso.setByteStream( fos );
                serializer.write( dom,lso );
               
            }
            catch( Exception e )
            {
                throw new RuntimeException(e);
            }
        }
    }
    
    /**
     * Loads a project from file.
     */
    public void loadProject( File path ) throws IOException, ParserConfigurationException, SAXException
    {
        if( !path.exists() || path.isDirectory() )
            return;

        if( path.getAbsolutePath().toLowerCase().endsWith( "." + PROJECT_EXTENSION ) )
        {
            DocumentBuilder db = dbFactory.newDocumentBuilder();

            Document dom = db.parse(path);

            Element docEle = dom.getDocumentElement();

            timeStep    .set( Double.parseDouble( docEle.getAttribute("dt"              ) ) );
            velocity    .set( Double.parseDouble( docEle.getAttribute("velocity"        ) ) );
            accel       .set( Double.parseDouble( docEle.getAttribute("acceleration"    ) ) );
            jerk        .set( Double.parseDouble( docEle.getAttribute("jerk"            ) ) );
            wheelBaseW  .set( Double.parseDouble( docEle.getAttribute("wheelBaseW"      ) ) );
            wheelBaseD  .set( Double.parseDouble( docEle.getAttribute("wheelBaseD"      ) ) );

            driveBase   .setValue( DriveBase.valueOf( docEle.getAttribute("driveBase"   ) ) );
            units       .setValue( Units    .valueOf( docEle.getAttribute("units"       ) ) );
            fitMethod   .setValue( FitMethod.valueOf( docEle.getAttribute("fitMethod"   ) ) );

            NodeList waypointEleList = docEle.getElementsByTagName( "Waypoint" );

            waypointList.clear();
            if( waypointEleList != null && waypointEleList.getLength() > 0 )
            {
                for( int i = 0; i < waypointEleList.getLength(); i++ )
                {
                    Element waypointEle = (Element) waypointEleList.item(i);

                    String
                            xText = waypointEle.getElementsByTagName("X").item(0).getTextContent(),
                            yText = waypointEle.getElementsByTagName("Y").item(0).getTextContent(),
                            angleText = waypointEle.getElementsByTagName("Angle").item(0).getTextContent();

                    waypointList.add( new Waypoint(
                                            Double.parseDouble( xText ),
                                            Double.parseDouble( yText ),
                                            Double.parseDouble( angleText )
                                        ) );
                }
            }

            workingProject = path;
        }
    }
    
    /**
     * Imports a properties (*.bot) file into the generator.
     * This import method should work with properties files generated from version 2.3.0.
     */
    public void importBotFile( File path, Units botUnits ) throws IOException, NumberFormatException {
        if( !path.exists() || path.isDirectory() )
            return;

        if( path.getAbsolutePath().toLowerCase().endsWith(".bot") )
        {
            BufferedReader botReader = new BufferedReader(new FileReader(path));
            Stream<String> botStream = botReader.lines();
            List<String> botLines = botStream.collect( Collectors.toList() );

            // First off we need to set the units of distance being used in the file.
            // Unfortunately it is not explicitly saved to file; we will need some user input on that.
            units.setValue( botUnits );

            // Now we can read the first 7 lines and assign them accordingly.
            timeStep    .set( Math.abs( Double.parseDouble( botLines.get(0).trim() ) ) );
            velocity    .set( Math.abs( Double.parseDouble( botLines.get(1).trim() ) ) );
            accel       .set( Math.abs( Double.parseDouble( botLines.get(2).trim() ) ) );
            jerk        .set( Math.abs( Double.parseDouble( botLines.get(3).trim() ) ) );
            wheelBaseW  .set( Math.abs( Double.parseDouble( botLines.get(4).trim() ) ) );
            wheelBaseD  .set( Math.abs( Double.parseDouble( botLines.get(5).trim() ) ) );

            fitMethod.setValue( FitMethod.valueOf("HERMITE_" + botLines.get(6).trim().toUpperCase()) );

            // Assume that the wheel base was swerve
            if( wheelBaseD.get() > 0 )
                driveBase.setValue( DriveBase.SWERVE );

            // GLHF parse the rest of the file I guess...
            for( int i = 7; i < botLines.size(); i++ )
            {
                String[] waypointVals = botLines.get(i).split("," );

                addPoint(   Double.parseDouble( waypointVals[0].trim() ),
                            Double.parseDouble( waypointVals[1].trim() ),
                            Math.toRadians( Double.parseDouble( waypointVals[2].trim() ) ) );
            }

            // Make sure you aren't trying to save to another project file
            clearWorkingFiles();
            botReader.close();
        }
    }
    
    /**
     * Clears the working project files
     */
    public void clearWorkingFiles()
    {
        workingProject = null;
    }
    
    /**
     * Resets configuration to default values for the given unit.
     */
    public void setDefaultValues( Units newUnits )
    {
        fitMethod.setValue( FitMethod.HERMITE_CUBIC );
        driveBase.setValue( DriveBase.TANK );
        units.setValue( newUnits );

        switch( newUnits )
    	{
        case FEET:
	        timeStep.set( 0.05 );
	        velocity.set( 4 );
	        accel.set( 3 );
	        jerk.set( 60 );
	        wheelBaseW.set( 1.464 );
	        wheelBaseD.set( 1.464 );
	        break;

        case METERS:
    		timeStep.set( 0.05 );
	        velocity.set( 1.2192 );
	        accel.set( 0.9144 );
	        jerk.set( 18.288 );
	        wheelBaseW.set( 0.4462272 );
	        wheelBaseD.set( 0.4462272 );
	        break;

        case INCHES:
    		timeStep.set( 0.05 );
	        velocity.set( 48 );
	        accel.set( 36 );
	        jerk .set( 720 );
	        wheelBaseW.set( 17.568 );
	        wheelBaseD.set( 17.568 );
	        break;
    	}
    }
    
    /**
     * Adds a waypoint to the list of waypoints
     */
    public void addPoint( double x, double y, double angle ) 
    {
        waypointList.add( new Waypoint( x, y, angle ) );
    }

    /**
     * Adds a waypoint to the list of waypoints
     */
    public void addPoint( Waypoint wp )
    {
        waypointList.add( wp );
    }
    
    /**
     * Edit a waypoint already in the list
     */
    public void editWaypoint( int index, double x, double y, double angle )
    {
        waypointList.get( index ).x = x;
        waypointList.get( index ).y = y;
        waypointList.get( index ).angle = angle;
    }
    
    public void removePoint( int index )
    {
        waypointList.remove( index );
    }

    public void removeLastPoint()
    {
        removePoint( waypointList.get().size() - 1 );
    }

    public void removePoints( int first, int last )
    {
        waypointList.remove( first, last + 1 );
    }

    public int getNumWaypoints()
    {
        return waypointList.size();
    }

    /**
     * Clears all the existing waypoints in the list.
     * This also clears all trajectories generated by the waywaypoints.
     */
    public void clearPoints() 
    {
        waypointList.clear();
    }
    
    public double getTimeStep()
    {
        return timeStep.get();
    }

    public void setTimeStep( double timeStep )
    {
        this.timeStep.set( timeStep );
    }

    public DoubleProperty timeStepProperty()
    {
        return timeStep;
    }

    public double getVelocity() 
    {
        return velocity.get();
    }

    public void setVelocity( double velocity )
    {
        this.velocity.set( velocity );
    }

    public DoubleProperty velocityProperty()
    {
        return velocity;
    }

    public double getAcceleration() 
    {
        return accel.get();
    }

    public void setAcceleration( double acceleration )
    {
        this.accel.set( acceleration );
    }

    public DoubleProperty accelProperty()
    {
        return accel;
    }

    public DriveBase getDriveBase()
    {
        return driveBase.getValue();
    }

    public void setDriveBase( DriveBase driveBase )
    {
        this.driveBase.setValue( driveBase );
    }

    public Property<DriveBase> driveBaseProperty()
    {
        return driveBase;
    }

    public FitMethod getFitMethod()
    {
        return fitMethod.getValue();
    }

    public void setFitMethod( FitMethod fitMethod )
    {
        this.fitMethod.setValue( fitMethod );
    }

    public Property<FitMethod> fitMethodProperty()
    {
        return  fitMethod;
    }

    public Units getUnits()
    {
        return units.getValue();
    }

    public void setUnits( Units units )
    {
        this.units.setValue( units );
    }

    public Property<Units> unitsProperty()
    {
        return units;
    }

    public double getJerk()
    {
        return jerk.get();
    }

    public void setJerk( double jerk )
    {
        this.jerk.set( jerk );
    }

    public DoubleProperty jerkProperty()
    {
        return jerk;
    }

    public double getWheelBaseW() 
    {
        return wheelBaseW.get();
    }

    public void setWheelBaseW( double wheelBaseW )
    {
        this.wheelBaseW.set( wheelBaseW );
    }

    public DoubleProperty wheelBaseWProperty()
    {
        return wheelBaseW;
    }

    public double getWheelBaseD()
    {
        return wheelBaseD.get();
    }

    public void setWheelBaseD( double wheelBaseD )
    {
        this.wheelBaseD.set( wheelBaseD );
    }

    public DoubleProperty wheelBaseDProperty()
    {
        return wheelBaseD;
    }
    
    public boolean hasWorkingProject()
    {
        return workingProject != null;
    }

    public ListProperty<Waypoint> waypointListProperty()
    {
        return waypointList;
    }

    public List<Waypoint> getWaypointList()
    {
        // TODO: What is happening when a ListProperty is converted to a List?
        return waypointList;
    }

    public Trajectory getSourceTrajectory()
    {
        return source.get();
    }

    public Trajectory getFrontLeftTrajectory() 
    {
        return fl.get();
    }

    public Trajectory getFrontRightTrajectory() 
    {
        return fr.get();
    }

    public Trajectory getBackLeftTrajectory() 
    {
        return bl.get();
    }

    public Trajectory getBackRightTrajectory() 
    {
        return br.get();
    }

    public ObjectBinding<Trajectory> getFronLeftTrajProperty()
    {
        return fl;
    }
}










