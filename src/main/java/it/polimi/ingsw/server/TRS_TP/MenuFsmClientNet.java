package it.polimi.ingsw.server.TRS_TP;


import it.polimi.ingsw.server.model.Date;
import it.polimi.ingsw.server.view.PlayerMove.InGameServerMessage;
import it.polimi.ingsw.server.view.PlayerMove.PlayerMove;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.AsynchronousCloseException;


public class MenuFsmClientNet {

    private ClientState currentClientState;
    private final ObjectOutputStream SocketobjectOutputStream;
    private final ObjectInputStream SocketobjectInputStream;

    private String playerName;
    private Date playerBirthday;

    private final Socket serverSocket;

    public MenuFsmClientNet(Socket serverSocket) throws IOException {
        this.serverSocket = serverSocket;
        this.SocketobjectOutputStream = new ObjectOutputStream(serverSocket.getOutputStream());
        this.SocketobjectInputStream = new ObjectInputStream(serverSocket.getInputStream());
        this.currentClientState = new ClientSetIdentityState(this);
        this.playerName = ClientViewAdapter.askForName();
        this.playerBirthday = ClientViewAdapter.askForDate();
    }

    public void setState(ClientState nextServerState) {
        currentClientState = nextServerState;
    }

    public void handleClientFsm() {
        currentClientState.handleClientFsm();
    }






    public String getPlayerName(){
        return playerName;
    }
    public Date getPlayerBirthday(){ return playerBirthday;}
    public void setPlayerName(String playerName){
        this.playerName = playerName;
    }
    public ObjectInputStream getOis() {
        return SocketobjectInputStream;
    }
    public ObjectOutputStream getOos() {
        return SocketobjectOutputStream;
    }
    public Socket getServerSocket() {
        return serverSocket;
    }





    public void run() {

        do{

            this.handleClientFsm();


            //finchè non sono arrivato all'ultimo stato non mi fermo
        }while(  !(this.currentClientState instanceof ClientFinalState)  );



        //*********************codice dell'ultimo stato


    }


}


interface ClientState {
    void handleClientFsm();
    void communicateWithTheServer();
}


class ClientSetIdentityState implements ClientState {

    private final MenuFsmClientNet fsmContext;

    public ClientSetIdentityState(MenuFsmClientNet fsmContext) {
        this.fsmContext = fsmContext;
    }


    @Override
    public void handleClientFsm() {

        this.communicateWithTheServer();
        //setto il prossimo stato
        fsmContext.setState(new ClientCreateOrParticipateState(fsmContext));
    }

    @Override
    public void communicateWithTheServer() {

        boolean canContinue = false;

        do{

            try {

                //Invio un messaggio con all'interno il nome scelto e il compleanno
                ConnectionManager.sendObject(new SetNameMessage(fsmContext.getPlayerName(), fsmContext.getPlayerBirthday()), fsmContext.getOos());


                //ricevo la risposta dal server
                SetNameMessage setNameMessageAnswer = (SetNameMessage) ConnectionManager.receiveObject(fsmContext.getOis());


                if(setNameMessageAnswer.typeOfMessage.equals(TypeOfMessage.Fail)){
                    ClientViewAdapter.printMessage(setNameMessageAnswer.errorMessage);
                    String newName = ClientViewAdapter.askForName();
                    //cambio il nome nel contesto della fsm
                    fsmContext.setPlayerName(newName);
                    canContinue = false;
                }


                if(setNameMessageAnswer.typeOfMessage.equals(TypeOfMessage.SetNameStateCompleted)){
                    //invio un messaggio di success
                    ClientViewAdapter.printMessage("Ho completato la fase di set del nome");
                    canContinue = true;
                }



            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }


        }while(!canContinue);
    }
}


class ClientCreateOrParticipateState implements ClientState {

    private final MenuFsmClientNet fsmContext;

    public ClientCreateOrParticipateState(MenuFsmClientNet fsmContext) {
        this.fsmContext = fsmContext;

    }



    @Override
    public void handleClientFsm() {

        this.communicateWithTheServer();
        //setto il prossimo stato
        fsmContext.setState(new ClientWaitingInLobbyState(fsmContext));

    }

    @Override
    public void communicateWithTheServer() {


        boolean canContinue = false;

        do{

            try {

                //chiedo al giocatore se vuole creare o partecipare ad una lobby
                boolean wantsToCreate = ClientViewAdapter.askIfPlayerWantsToCreate();


                if( wantsToCreate ) {
                    //ho chiesto le info necessarie al client e mi ha risposto, posso inviare i dati al server
                    MenuMessages menuMessage = ClientViewAdapter.askForInfoToCreateLobby(fsmContext.getPlayerName());
                    ConnectionManager.sendObject(menuMessage, fsmContext.getOos());

                }

                //il client ha deciso di partecipare ad una lobby
                else {

                    //chiedo se vuole partecipare ad una lobby pubblica o privata
                    boolean wantsLobbyPublic = ClientViewAdapter.askIfWantsToParticipateLobbyPublic();
                    //chiedo informazioni sulla lobby in questione
                    MenuMessages menuMessage = ClientViewAdapter.askForInfoToParticipateLobby(wantsLobbyPublic, fsmContext.getPlayerName());
                    ConnectionManager.sendObject(menuMessage, fsmContext.getOos());
                }


                //ricevo la risposta dal server
                MenuMessages serverAnswer = (MenuMessages) ConnectionManager.receiveObject(fsmContext.getOis());


                if(serverAnswer.typeOfMessage.equals(TypeOfMessage.Fail)){
                    //non vado avanti
                    ClientViewAdapter.printMessage(serverAnswer.errorMessage);
                    canContinue = false;
                }


                if(serverAnswer.typeOfMessage.equals(TypeOfMessage.CreateOrParticipateStateCompleted)){
                    //invio un messaggio di success
                    ClientViewAdapter.printMessage(serverAnswer.errorMessage);
                    canContinue = true;
                }

            } catch (IOException e) {
                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }


        }while(!canContinue);


    }


}


class ClientWaitingInLobbyState implements ClientState {

    private final MenuFsmClientNet fsmContext;

    private boolean hasToWait = true;


    public ClientWaitingInLobbyState(MenuFsmClientNet fsmContext) {
        this.fsmContext = fsmContext;

    }


    @Override
    public void handleClientFsm() {

        this.communicateWithTheServer();
        //setto il prossimo stato
        this.hasToWait = false;
        fsmContext.setState(new ClientInGameState(fsmContext));


    }

    @Override
    public void communicateWithTheServer() {

        boolean canContinueToInGameState = false;

        ClientMain.clientExecutor.submit(new WaitingCompanion());

        do{

            try {

                WaitingInLobbyMessages waitingInLobbyMessage = (WaitingInLobbyMessages) ConnectionManager.receiveObject(fsmContext.getOis());

                switch (waitingInLobbyMessage.typeOfMessage) {


                    case WaitingInLobbyStateCompleted:
                        ClientViewAdapter.printMessage("The lobby is full, now you can start playing!");
                        canContinueToInGameState = true;
                        break;


                    case WaitingInLobbyDisconnected:
                        ClientViewAdapter.printMessage("Disconnected from the lobby");

                        break;


                    case WaitingInLobbyPlayerDisconnected:

                        ClientViewAdapter.printMessage(waitingInLobbyMessage.getNameOfPlayer() + " has disconnected from the lobby");
                        canContinueToInGameState = false;
                        break;


                    case WaitingInLobbyPlayerJoined:

                        ClientViewAdapter.printMessage(waitingInLobbyMessage.getNameOfPlayer() + " has joined the lobby");
                        canContinueToInGameState = false;
                        break;


                }


            } catch(SocketException | AsynchronousCloseException e){

                try {
                    fsmContext.getServerSocket().close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

            } catch (IOException e) {

                e.printStackTrace();
            } catch (ClassNotFoundException e) {
                e.printStackTrace();
            }


        }while(!canContinueToInGameState);


    }



    private class WaitingCompanion implements Runnable{

        @Override
        public void run() {

            ClientViewAdapter.printMessage("Waiting in lobby");

            do {

                System.out.printf("#");

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

            }while(hasToWait);
        }
    }




}


class ClientInGameState implements ClientState {

    private final MenuFsmClientNet fsmContext;


    public ClientInGameState(MenuFsmClientNet fsmContext) {
        this.fsmContext = fsmContext;

    }


    @Override
    public void handleClientFsm() {

        this.communicateWithTheServer();
        //setto il prossimo stato
        fsmContext.setState(new ClientFinalState(fsmContext));


    }

    @Override
    public void communicateWithTheServer() {

       PlayerMove playerMoveInitial = ClientViewAdapter.askForGodName(" ");

       try{ ConnectionManager.sendObject(playerMoveInitial, fsmContext.getOos());}
       catch (IOException e) {
           e.printStackTrace();
       }

        boolean canContinueToFinalState = false;

        do {
            try {
                InGameServerMessage serverMessage = (InGameServerMessage) ConnectionManager.receiveObject(fsmContext.getOis());
                //provvisorio
                serverMessage.getBoardPhotography().show();

                switch (serverMessage.getModelMessage().getModelMessageType()) {

                    case GameOver:
                        canContinueToFinalState = true;
                        break;


                    case NeedsConfirmation:
                        //invio il messaggio con la stringa relativa
                        PlayerMove playerMoveConfirmation = ClientViewAdapter.askForInGameConfirmation(serverMessage.getModelMessage().getMessage());
                        ConnectionManager.sendObject(playerMoveConfirmation, fsmContext.getOos());
                        break;


                    case NeedsGodName:
                        //invio il messaggio con la stringa relativa
                        PlayerMove playerMoveGodName = ClientViewAdapter.askForGodName(serverMessage.getModelMessage().getMessage());
                        ConnectionManager.sendObject(playerMoveGodName, fsmContext.getOos());
                        break;


                    case NeedsCoordinates:
                        //invio il messaggio con la stringa relativa
                        PlayerMove playerMoveCoordinates = ClientViewAdapter.askForCoordinates(serverMessage.getModelMessage().getMessage());
                        ConnectionManager.sendObject(playerMoveCoordinates, fsmContext.getOos());
                        break;

                    default:
                }
            } catch(IOException | ClassNotFoundException e){ e.printStackTrace(); }

        } while(!canContinueToFinalState);
    }

}

    /*@Override
    public void communicateWithTheServer() {

        ClientMessageReceiver messageReceiver = new ClientMessageReceiver();
        ClientInGameConnection gameConnection = new ClientInGameConnection();

        gameConnection.addObserver(messageReceiver);


    }*/


    /*class ClientInGameConnection extends ObservableVC<InGameServerMessage> {



        public void handleConnection() {
            boolean canContinueToFinalState = false;

            do {

                try {

                    InGameServerMessage serverMessage = (InGameServerMessage) ois.readObject();
                    notify(serverMessage);

                    //ho ricevuto un messaggio di gameover posso continuare al final state
                    if(serverMessage.getModelMessage().getModelMessageType() == ModelMessageType.GameOver) {
                        canContinueToFinalState = true;
                    }

                } catch (IOException | ClassNotFoundException e) {
                    e.printStackTrace();
                }

            } while (!canContinueToFinalState);


        }
    }


    private class ClientMessageReceiver implements ObserverVC<InGameServerMessage> {


        @Override
        public void update(InGameServerMessage message) {

            switch(message.getModelMessage().getModelMessageType()){

                case GameOver :
                    //
                    break;


                case NeedsConfirmation :
                    //invio il messaggio con la stringa relativa
                    PlayerMove playerMoveConfirmation = ClientViewAdapter.askForInGameConfirmation(message.getModelMessage().getMessage());
                    break;


                case NeedsGodName :
                    //invio il messaggio con la stringa relativa
                    PlayerMove playerMoveGodName = ClientViewAdapter.askForGodName(message.getModelMessage().getMessage());
                    break;


                case NeedsCoordinates :
                    //invio il messaggio con la stringa relativa
                    PlayerMove playerMoveCoordinates = ClientViewAdapter.askForCoordinates(message.getModelMessage().getMessage());
                    break;

                default :


            }









        }



    }*/


class ClientFinalState implements ClientState {

    private final MenuFsmClientNet fsmContext;



    public ClientFinalState(MenuFsmClientNet fsmContext) {
        this.fsmContext = fsmContext;

    }

    @Override
    public void handleClientFsm() {

    }

    @Override
    public void communicateWithTheServer() {

    }
}


