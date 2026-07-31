/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package cydi;
import java.util.ArrayList;
import java.util.List;
import org.joml.Vector3f;



/**
 *
 * @author Jesse
 */
public class Face {
    
    public Vector3f indice = new Vector3f();
    public Vector3f normal = new Vector3f();
    public Face(Vector3f indice, Vector3f normal) {
        this.indice = indice; 
        this.normal = normal;
    }
}
