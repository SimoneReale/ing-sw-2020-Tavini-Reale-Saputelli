package it.polimi.ingsw.server.transmissionprotocol;

import it.polimi.ingsw.bothsides.ConnectionManager;
import it.polimi.ingsw.bothsides.onlinemessages.InGameServerMessage;
import it.polimi.ingsw.bothsides.onlinemessages.modelmessage.ModelMessage;
import it.polimi.ingsw.bothsides.onlinemessages.modelmessage.ModelMessageType;
import it.polimi.ingsw.bothsides.utils.Global;
import it.polimi.ingsw.server.observers.Observable;
import it.polimi.ingsw.bothsides.utils.LogPrinter;
import it.polimi.ingsw.bothsides.onlinemessages.playermove.*;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;

/**
 * this class is the one that deals with the reception of playermoves from client and sends InGameServerMessages to the client
 * it is instantiated in serverFSM and passed as a parameter to the RemoteView of that player
 *
 */
//ricordarsi della gestione degli errori
public class InGameConnection extends Observable<PlayerMove> implements Runnable{


        private ObjectOutputStream oos;
        private ObjectInputStream ois;
        private String uniquePlayerCode;
        private boolean openedConnection = false;
        private final ServerFsm fsmContext;


    /**
     * the constructor is strictly related to the corresponding ServerFSM
     *
     * @param uniquePlayerCode inherited from ServerFSM
     * @param oos inherited from ServerFSM
     * @param ois inherited from ServerFSM
     * @param fsmContext status of the ServerFSM
     */
        public InGameConnection(String uniquePlayerCode, ObjectOutputStream oos, ObjectInputStream ois, ServerFsm fsmContext) {

            this.uniquePlayerCode = uniquePlayerCode;
            this.oos = oos;
            this.ois = ois;
            this.fsmContext = fsmContext;
        }

        public String getUniquePlayerCode() {
        return uniquePlayerCode;
    }

    /**
     * this method is used to send the BoardPhotograpy and the ModelMessage every time update is called in remoteview
     * exception are printed on a specific Log
     *
     * @param inGameServerMessage passed from the remote view
     */
    public void sendInGameServerMessage(InGameServerMessage inGameServerMessage) {
        try {

            ConnectionManager.sendObject(inGameServerMessage, oos);

        } catch (IOException e) {
            LogPrinter.printOnLog(Global.INGAMECONNECTIONWASNTABLETOSENDMODELMESSAGE);
            LogPrinter.printOnLog(e.toString());

        }
    }



    /**
     * this method, while openedConnection is true continues to read playermoves from the ObjectInputStream
     * and usually notifies them to observers (the MessageReceiver of the RemoteView
     * if the type of the playermove is CHAT_MESSAGE
     *
     * if an exception is given by irregular behaviour of the OIS (for example if the player closes the game)
     * the connection ends and other players are disconnected as corresponding ServerFSM calls killLobby
     *
     * informations on the exceptions launched is saved on a log file
     */
        @Override
        public void run() {

            openedConnection = true;

            LogPrinter.printOnLog(Global.JUSTRUNINGAMECONNECTIONOFTHEFOLLOWINGPLAYER +ServerThread.ListIdentities.retrievePlayerName(getUniquePlayerCode()));

            try{

                while(openedConnection){

                    PlayerMove playerMove = (PlayerMove) ois.readObject();

                    if(playerMove.getType() == PlayerMoveType.CHAT_MESSAGE) {

                        fsmContext.getAssignedLobby().getLobbyChat().addMessage(playerMove.getGenericMessage());

                    }

                    if(playerMove.getType() == PlayerMoveType.KILL_IN_GAME_CONNECTION) {

                        this.openedConnection = false;
                    }

                    else notify(playerMove, null);


                }


            } catch(Exception e){

               ServerFsm fsm = ServerThread.getFsmByUniqueCode(uniquePlayerCode);

                try {

                    fsm.getAssignedLobby().killLobby();

                } catch (IOException ex) {
                    LogPrinter.printOnLog(Global.ITDIDNTKILLTHELOBBYINGAMECONNECTION);
                    LogPrinter.printOnLog(e.toString());
                }

                LogPrinter.printOnLog(Global.INGAMECONNECTIONFAILEDTORECEIVEPLAYERMOVE);
                LogPrinter.printOnLog(e.toString());

            }

        }

    }
