package it.polimi.ingsw.server.transmissionprotocol;

import it.polimi.ingsw.bothsides.ConnectionManager;
import it.polimi.ingsw.bothsides.onlinemessages.setupmessages.PingAndErrorMessage;
import it.polimi.ingsw.bothsides.onlinemessages.setupmessages.TypeOfSetupMessage;
import it.polimi.ingsw.bothsides.utils.LogPrinter;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;


public class AsyncronousPingAndErrorHandler implements Runnable {


    private final Socket clientSocket;
    private boolean isActive = true;

    private final ObjectOutputStream oos;
    private final ObjectInputStream ois;

    private String namePlayer;
    private boolean hasNameBeenSet = false;

    public AsyncronousPingAndErrorHandler(Socket clientSocket) throws IOException {
        this.clientSocket = clientSocket;
        this.ois = new ObjectInputStream(clientSocket.getInputStream());
        this.oos = new ObjectOutputStream(clientSocket.getOutputStream());
    }


    @Override
    public void run() {

        PingAndErrorMessage pingMessage = new PingAndErrorMessage(TypeOfSetupMessage.PingAndErrorMessagePing, "Ping");

        do {

            try {

                Thread.sleep(1000);
                ConnectionManager.sendObject(pingMessage, this.oos);
                PingAndErrorMessage answer = (PingAndErrorMessage) ConnectionManager.receiveObject(ois);
                if( !hasNameBeenSet ){
                    namePlayer = answer.errorMessage;
                    hasNameBeenSet = true;
                }

            } catch (Exception e) {

                isActive = false;
                LogPrinter.printOnLog("\n----Something went wrong in the ping handler----");
                LogPrinter.printOnLog("\n" +e.toString());


                String uniquePlayerCode = ServerThread.ListIdentities.retrievePlayerIdentityByName(namePlayer).getUniquePlayerCode();

                ServerFsm fsmContext = ServerThread.getFsmByUniqueCode(uniquePlayerCode);


                if(fsmContext.getAssignedLobby() != null){

                    try {

                        fsmContext.getAssignedLobby().removeFsmClientHandlerFromList(ServerThread.ListIdentities.retrievePlayerIdentity(fsmContext.getUniquePlayerCode()));

                    } catch (IOException ex) {
                        LogPrinter.printOnLog("\n----Couldn't remove player from assigned lobby----");
                        LogPrinter.printOnLog(e.toString());
                    }
                }

                fsmContext.setEverythingOkFalse();
                ServerThread.ListIdentities.removePlayerFromListIdentities(fsmContext.getUniquePlayerCode());
                Thread.currentThread().interrupt();


                try {
                    clientSocket.close();
                } catch (IOException ex) {
                    LogPrinter.printOnLog("\n----AsyncronousPingHandler was not able to close the connection----");
                    Thread.currentThread().interrupt();
                }

            }

        }while (isActive);

    }
}