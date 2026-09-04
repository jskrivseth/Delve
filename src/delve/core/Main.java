/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */
package delve.core;

/**
 *
 * @author Jesse
 */
public class Main {

    public static void main(String[] args) {
        //CubeTest demo = new CubeTest();
        //demo.run(false);
        try {
            Game game = new Game();
            game.play(false);
        } catch (Exception e) {
            System.out.println("Exception " + e.getMessage());
        }
    }
}
