package it.polimi.ingsw.server.model;

public class Worker {

    private final Player player;
    private final Color colour;
    private final String workerTag;

    private Box occupiedBox;

    public Worker(Player player, Color color, String workerTag){

        //tutto in upperCase per uniformità
        this.colour = color ;
        this.workerTag = workerTag;
        this.player = player;
    }

    public Color getColour() {
        return colour;
    }

    public Player getPlayer() {
        return player;
    }

    public String getWorkerTag() {
        return workerTag;
    }

    public Box getOccupiedBox(){ return this.occupiedBox; }

    @Override
    public boolean equals(Object obj) {
        if(!(obj instanceof Worker)) return false;
        if( ((Worker) obj).player.equals(this.player) && ((Worker) obj).colour.equals(this.colour) && ((Worker) obj).workerTag.equals(this.workerTag))
            return true;
        else return false;
    }
}
