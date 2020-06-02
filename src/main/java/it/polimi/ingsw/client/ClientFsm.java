package it.polimi.ingsw.client;


import it.polimi.ingsw.bothsides.ConnectionManager;
import it.polimi.ingsw.bothsides.onlinemessages.*;
import it.polimi.ingsw.bothsides.onlinemessages.modelmessage.ModelError;
import it.polimi.ingsw.bothsides.onlinemessages.modelmessage.ModelMessageType;
import it.polimi.ingsw.bothsides.onlinemessages.setupmessages.*;
import it.polimi.ingsw.bothsides.utils.Global;
import it.polimi.ingsw.bothsides.utils.LogPrinter;
import it.polimi.ingsw.bothsides.onlinemessages.BoardPhotography;
import it.polimi.ingsw.server.model.Date;
import it.polimi.ingsw.bothsides.onlinemessages.modelmessage.ModelMessage;
import it.polimi.ingsw.bothsides.utils.ColorAnsi;
import it.polimi.ingsw.bothsides.onlinemessages.playermove.PlayerMove;

import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.Socket;
import java.net.SocketException;
import java.nio.channels.AsynchronousCloseException;
import java.util.NoSuchElementException;

import static it.polimi.ingsw.client.ClientMain.clientExecutor;


public class ClientFsm {

    private ClientState currentClientState;
    private final ObjectOutputStream socketobjectOutputStream;
    private final ObjectInputStream socketobjectInputStream;

    private String playerName;
    private Date playerBirthday;

    private final Socket serverSocket;
    private ClientPingAndErrorThread pingAndErrorThread = null;

    public ClientFsm(Socket serverSocket) throws IOException {
        this.serverSocket = serverSocket;
        this.socketobjectOutputStream = new ObjectOutputStream(serverSocket.getOutputStream());
        this.socketobjectInputStream = new ObjectInputStream(serverSocket.getInputStream());

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
        return socketobjectInputStream;
    }
    public ObjectOutputStream getOos() {
        return socketobjectOutputStream;
    }
    public Socket getServerSocket() {
        return serverSocket;
    }
    public ClientPingAndErrorThread getPingAndErrorThread() {
        return pingAndErrorThread;
    }
    public void setPingAndErrorThread(ClientPingAndErrorThread pingAndErrorThread) {
        this.pingAndErrorThread = pingAndErrorThread;
    }

    public void sendChatMessage(PlayerMove chatMessage) {

        try {

            ConnectionManager.sendObject(chatMessage, socketobjectOutputStream);

        } catch (IOException e) {
            LogPrinter.printOnLog(Global.CHATERROR);
            LogPrinter.printOnLog(e.getStackTrace().toString());
            Thread.currentThread().interrupt();
        }

    }





    public void run() {

        do{

            this.handleClientFsm();

            //finchè non sono arrivato all'ultimo stato non mi fermo
        }while(  !(this.currentClientState instanceof ClientEndState)  );



        //*********************codice dell'ultimo stato
        this.handleClientFsm();


    }


}


interface ClientState {
    void handleClientFsm();
    void communicateWithTheServer();
}


class ClientSetIdentityState implements ClientState {

    private final ClientFsm fsmContext;

    public ClientSetIdentityState(ClientFsm fsmContext) {
        this.fsmContext = fsmContext;
    }

    private void askingNewName(SetNameMessage setNameMessageAnswer){
        ClientViewAdapter.printMenuMessage(setNameMessageAnswer.errorMessage);
        String newName = ClientViewAdapter.askForName();

        //cambio il nome nel contesto della fsm
        fsmContext.setPlayerName(newName);
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
                ConnectionManager.sendObject(SetNameMessage.newSetNameMessageComplete(fsmContext.getPlayerName(), fsmContext.getPlayerBirthday()), fsmContext.getOos());

                //ricevo la risposta dal server
                SetNameMessage setNameMessageAnswer = (SetNameMessage) ConnectionManager.receiveStandardObject(fsmContext.getOis());

                if(setNameMessageAnswer.typeOfSetupMessage.equals(TypeOfSetupMessage.FAIL)){

                    askingNewName(setNameMessageAnswer);
                    canContinue = false;

                }


                if(setNameMessageAnswer.typeOfSetupMessage.equals(TypeOfSetupMessage.SET_NAME_STATE_COMPLETED)){
                    //invio un messaggio di success
                    ClientViewAdapter.printMenuMessage(Global.IDENTITYSET);
                    canContinue = true;
                }



            } catch (IOException | ClassNotFoundException e) {
                LogPrinter.printOnLog(Global.IDENTITYSTATECLIENTERROR);
                LogPrinter.printOnLog(e.getStackTrace().toString());

            }

        } while(!canContinue);
    }

}


class ClientCreateOrParticipateState implements ClientState {

    private final ClientFsm fsmContext;

    private boolean jumpToInGameState = false;

    public ClientCreateOrParticipateState(ClientFsm fsmContext) {
        this.fsmContext = fsmContext;
    }

    private void creatingLobby(ClientFsm fsmContext) throws IOException {
        MenuMessage menuMessage = ClientViewAdapter.askForInfoToCreateLobby(fsmContext.getPlayerName());
        ConnectionManager.sendObject(menuMessage, fsmContext.getOos());
    }

    private void joiningLobby(boolean wantsLobbyPublic, ClientFsm fsmContext) throws IOException {
        MenuMessage menuMessage = ClientViewAdapter.askForInfoToParticipateLobby(wantsLobbyPublic, fsmContext.getPlayerName());
        ConnectionManager.sendObject(menuMessage, fsmContext.getOos());
    }


    @Override
    public void handleClientFsm() {


        //fa partire il thread che gestisce i ping
        if(fsmContext.getPingAndErrorThread() == null) {

            ClientPingAndErrorThread clientPingAndErrorThread = new ClientPingAndErrorThread(ClientMain.getErrorChannel(), fsmContext.getPlayerName());
            fsmContext.setPingAndErrorThread(clientPingAndErrorThread);
            clientExecutor.submit(clientPingAndErrorThread);

        }


        this.communicateWithTheServer();
        //setto il prossimo stato

        if(!jumpToInGameState)fsmContext.setState(new ClientWaitingInLobbyState(fsmContext));

        if(jumpToInGameState)fsmContext.setState(new ClientInGameState(fsmContext));

    }

    @Override
    public void communicateWithTheServer() {


        boolean canContinue = false;

        do{

            try {

                //chiedo al giocatore se vuole creare o partecipare ad una lobby
                boolean wantsToCreate = ClientViewAdapter.askBooleanQuestion(Global.DOYOUWANTTOCREATEANEWLOBBY);


                if( wantsToCreate ) {

                    //ho chiesto le info necessarie al client e mi ha risposto, posso inviare i dati al server
                    creatingLobby(fsmContext);

                }

                //il client ha deciso di partecipare ad una lobby
                else {

                    //chiedo se vuole partecipare ad una lobby pubblica o privata
                    boolean wantsLobbyPublic = ClientViewAdapter.askBooleanQuestion(Global.DOYOUWANTTOJOINAPUBLICLOBBY);

                    //chiedo informazioni sulla lobby in questione
                    joiningLobby(wantsLobbyPublic, fsmContext);

                }

                //ricevo la risposta dal server
                MenuMessage serverAnswer = (MenuMessage) ConnectionManager.receiveStandardObject(fsmContext.getOis());


                if(serverAnswer.typeOfSetupMessage.equals(TypeOfSetupMessage.FAIL)){
                    //non vado avanti
                    ClientViewAdapter.printMenuMessage(serverAnswer.errorMessage);
                    jumpToInGameState = false;
                    canContinue = false;
                }


                if(serverAnswer.typeOfSetupMessage.equals(TypeOfSetupMessage.CREATE_OR_PARTICIPATE_STATE_COMPLETED)){
                    //invio un messaggio di success
                    ClientViewAdapter.printMenuMessage(serverAnswer.errorMessage);
                    jumpToInGameState = false;
                    canContinue = true;
                }


                if(serverAnswer.typeOfSetupMessage == TypeOfSetupMessage.CHOOSE_PARTECIPATE_CAN_JUMP_TO_IN_GAME_STATE){
                    //il client ha completato la lobby e può passare direttamente all'ingame state
                    jumpToInGameState = true;
                    ClientViewAdapter.printMenuMessage(Global.TIMETOPLAY);
                    canContinue = true;

                }



            } catch (IOException | ClassNotFoundException e) {
                LogPrinter.printOnLog(Global.CHOOSEORCREATESTATECLIENTERROR);
                LogPrinter.printOnLog(e.getStackTrace().toString());
            }

        }while(!canContinue);

    }

}


class ClientWaitingInLobbyState implements ClientState {

    private final ClientFsm fsmContext;
    private boolean hasToWait = true;


    public ClientWaitingInLobbyState(ClientFsm fsmContext) {
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

        if( !ClientViewAdapter.isMenuInterfaceAGui() )clientExecutor.submit(new CliWaitingCompanion());

        else MenuGui.setWaitInLobby();

        do{

            try {

                WaitingInLobbyMessage waitingInLobbyMessage = (WaitingInLobbyMessage) ConnectionManager.receiveStandardObject(fsmContext.getOis());

                switch (waitingInLobbyMessage.typeOfSetupMessage) {


                    case WAITING_IN_LOBBY_STATE_COMPLETED:
                        ClientViewAdapter.printMenuMessage(Global.LOBBYFULL);
                        canContinueToInGameState = true;
                        break;


                    case WAITING_IN_LOBBY_PLAYER_DISCONNECTED:

                        ClientViewAdapter.printMenuMessage(waitingInLobbyMessage.getNameOfPlayer() + Global.HASDISCONNECTEDFROMLOBBY);
                        canContinueToInGameState = false;
                        break;


                    case WAITING_IN_LOBBY_PLAYER_JOINED:

                        ClientViewAdapter.printMenuMessage(waitingInLobbyMessage.getNameOfPlayer() + Global.HASJOINEDTHELOBBY);
                        canContinueToInGameState = false;
                        break;


                    default:
                        canContinueToInGameState = false;
                        break;


                }



            } catch(SocketException | AsynchronousCloseException e){

                try {
                    fsmContext.getServerSocket().close();
                } catch (IOException ex) {
                    ex.printStackTrace();
                }

            } catch (IOException | ClassNotFoundException e) {
                LogPrinter.printOnLog(Global.WAITINGSTATECLIENTERROR);
                LogPrinter.printOnLog(e.getStackTrace().toString());
            }


        }while(!canContinueToInGameState);


    }



    private class CliWaitingCompanion implements Runnable{

        @Override
        public void run() {

            ClientViewAdapter.printMenuMessage(Global.WAITINGINLOBBY);

            do {

                System.out.printf("%s", Global.HASHTAG);

                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e) {
                    LogPrinter.printOnLog(Global.WAITINGSTATECLIENTERROR);
                    LogPrinter.printOnLog(e.getStackTrace().toString());
                    Thread.currentThread().interrupt();
                }

            }while(hasToWait);
        }
    }




}


class ClientInGameState implements ClientState {

    private final ClientFsm fsmContext;
    private boolean canContinueToFinalState;
    private ModelMessage currentModelMessage;


    public ClientInGameState(ClientFsm fsmContext) {

        this.fsmContext = fsmContext;
        this.canContinueToFinalState = false;
        this.currentModelMessage = null;

    }


    @Override
    public void handleClientFsm() {

        ClientViewAdapter.printInGameMessage(ColorAnsi.RED +Global.TIMETOPLAY +ColorAnsi.RESET);
        //avvio la transizione da menu a ingame gui
        ClientViewAdapter.fromMenuToInGameGui();
        this.communicateWithTheServer();
        ClientViewAdapter.fromInGameGuiToMenu();
        //setto il prossimo stato
        fsmContext.setState(new ClientChoiceNewGameState(fsmContext));


    }

    @Override
    public void communicateWithTheServer() {

        try{

            Thread inGameIoHandler = new Thread(new InGameIoHandler());
            inGameIoHandler.start();
            inGameIoHandler.join();


        } catch(InterruptedException | NoSuchElementException e){
            LogPrinter.printOnLog(Global.CLIENTCLOSED);
            LogPrinter.printOnLog(e.getStackTrace().toString());
            //da vedere
            Thread.currentThread().interrupt();
        }
    }


    private class InGameIoHandler implements Runnable {

        @Override
        public void run() {

            try {

                BoardPhotography boardPhotography = null;
                ModelMessage modelMessage = null;

                while (!canContinueToFinalState) {


                    Object inputObject = ConnectionManager.receiveStandardObject(fsmContext.getOis());

                    if (inputObject instanceof InGameServerMessage) {

                        boardPhotography = ((InGameServerMessage) inputObject).getBoardPhotography();
                        modelMessage = ((InGameServerMessage) inputObject).getModelMessage();

                        if (boardPhotography != null) {

                            ClientViewAdapter.updateBoard(boardPhotography);
                        }

                        ClientViewAdapter.printSecondaryInGameMessage(Global.BACKSLASHN);

                        if ( packetFilter(modelMessage) ) {

                            if(modelMessage.getModelError() != ModelError.NONE) {

                                ClientViewAdapter.printSecondaryInGameMessage(Global.NOTALLOWED +modelMessage.getModelError().toString()+Global.BACKSLASHN);
                            }

                            if(modelMessage.getModelMessageType() != ModelMessageType.CHAT_MESSAGE){

                                ClientViewAdapter.printInGameMessage(modelMessage.getMessage());
                            }


                            if(!isBlockingHandleNeeded(modelMessage)) {

                                new Thread(new HandleModelMessageClassNonBlocking(modelMessage)).start();

                            }


                            else handleModelMessageBlocking(modelMessage);


                        }

                        else {

                            new Thread(new HandleModelMessageClassNonBlocking(new ModelMessage(ModelMessageType.WAIT, Global.SPACE))).start();

                        }

                    }

                }

                System.out.println("Sono uscito dal while nell'inGame handler");

            } catch (Exception e){
                LogPrinter.printOnLog(Global.READSERVERMESSAGEFAILED);
                LogPrinter.printOnLog(e.getStackTrace().toString());
                //qua va portato a false canContinueToFinalState?
            }
        }

        private boolean isBlockingHandleNeeded(ModelMessage modelMessage) {


            return modelMessage.getModelMessageType() == ModelMessageType.DISCONNECTED ||
                    modelMessage.getModelMessageType() == ModelMessageType.GAMEOVER ||
                    modelMessage.getModelMessageType() == ModelMessageType.YOULOST;
        }

        private boolean packetFilter(ModelMessage modelMessage) {

            return modelMessage != null && (modelMessage.getModelMessageType().equals(ModelMessageType.GODHASBEENCHOSEN) ||
                    modelMessage.getModelMessageType().equals(ModelMessageType.GAMEOVER) ||
                    modelMessage.getModelMessageType().equals(ModelMessageType.YOULOST) ||
                    modelMessage.getModelMessageType().equals(ModelMessageType.DISCONNECTED) ||
                    modelMessage.getModelMessageType().equals(ModelMessageType.CHAT_MESSAGE) ||
                    modelMessage.getCurrentPlayer().equals(fsmContext.getPlayerName()));



        }

        private class HandleModelMessageClassNonBlocking implements Runnable {

            private final ModelMessage modelMessage;

            HandleModelMessageClassNonBlocking(ModelMessage modelMessage) {

                this.modelMessage = modelMessage;

            }

            private void handleModelMessageNonBlocking(ModelMessage modelMessage) throws IOException {

                switch (modelMessage.getModelMessageType()) {


                    case CHAT_MESSAGE:

                        ClientViewAdapter.refreshChat(modelMessage.getMessage());
                        break;


                    case CONFIRMATION:
                        //invio il messaggio con la stringa relativa
                        PlayerMove playerMoveConfirmation = ClientViewAdapter.askForInGameConfirmation(modelMessage.getMessage());
                        ConnectionManager.sendObject(playerMoveConfirmation, fsmContext.getOos());
                        break;


                    case GODNAME:
                        //invio il messaggio con la stringa relativa
                        PlayerMove playerMoveGodName = ClientViewAdapter.askForGodName(modelMessage.getMessage());
                        ConnectionManager.sendObject(playerMoveGodName, fsmContext.getOos());
                        break;


                    case COORDINATES:
                        //invio il messaggio con la stringa relativa
                        PlayerMove playerMoveCoordinates = ClientViewAdapter.askForCoordinates(modelMessage.getMessage());
                        ConnectionManager.sendObject(playerMoveCoordinates, fsmContext.getOos());
                        break;

                    case GODHASBEENCHOSEN:
                        if (modelMessage.getCurrentPlayer().equals(fsmContext.getPlayerName())) {
                            ClientViewAdapter.showChosenGods(modelMessage, true);
                        }
                        else {
                            ClientViewAdapter.showChosenGods(modelMessage, false);
                        }
                        break;

                    case WAIT:
                        ClientViewAdapter.printInGameMessage(Global.WAITYOURTURN);
                        break;

                    default:
                        ClientViewAdapter.printInGameMessage(Global.INCORRECTPLAYERMOVE);
                        break;
                }


            }


            @Override
            public void run() {

                try {
                    handleModelMessageNonBlocking(this.modelMessage);
                } catch (IOException e) {
                    LogPrinter.printOnLog(Global.HANDLEMODELMESSAGEERROR);
                    LogPrinter.printOnLog(e.getStackTrace().toString());
                    Thread.currentThread().interrupt();
                }

            }


        }

        private void handleModelMessageBlocking(ModelMessage modelMessage) throws IOException {

            switch (modelMessage.getModelMessageType()) {

                case DISCONNECTED:
                    ClientViewAdapter.printInGameMessage(Global.YOUHAVEBEENDISCONNECTED);
                    ClientMain.closeConnectionChannels();
                    canContinueToFinalState = true;
                    break;

                case YOULOST:

                    ConnectionManager.sendObject(PlayerMove.buildKillerPlayerMove(), fsmContext.getOos());
                    canContinueToFinalState = true;
                    break;

                case GAMEOVER:

                    ConnectionManager.sendObject(PlayerMove.buildKillerPlayerMove(), fsmContext.getOos());
                    canContinueToFinalState = true;
                    break;


                default:
                    ClientViewAdapter.printInGameMessage(Global.INCORRECTPLAYERMOVE);
                    break;
            }


        }





    }






}


class ClientChoiceNewGameState implements ClientState {

    private final ClientFsm fsmContext;
    private boolean wantsToContinue = false;


    public ClientChoiceNewGameState(ClientFsm fsmContext) {
        this.fsmContext = fsmContext;

    }

    @Override
    public void handleClientFsm() {

        System.out.println("Sono nella handle del ClientChoice ");

        this.communicateWithTheServer();

        if(wantsToContinue){ fsmContext.setState(new ClientCreateOrParticipateState(fsmContext)); }

        else fsmContext.setState(new ClientEndState());

    }

    @Override
    public void communicateWithTheServer() {

        FinalStateMessage finalAnswer = null;

        try {


            ConnectionManager.receiveStandardObject(fsmContext.getOis());

            System.out.println("Sto dopo la receive standard object");

            boolean wantsToRestart = ClientViewAdapter.askBooleanQuestion("Do you want to restart?");


            if(wantsToRestart){
                wantsToContinue = true;
                finalAnswer = FinalStateMessage.newFinalStateMessageAnswer(true);
                ConnectionManager.sendObject(finalAnswer, fsmContext.getOos());

            }

            else{
                wantsToContinue = false;
                finalAnswer = FinalStateMessage.newFinalStateMessageAnswer(false);
                ConnectionManager.sendObject(finalAnswer, fsmContext.getOos());

            }


        } catch (Exception e) {

            ClientViewAdapter.printMenuMessage("FATAL ERROR IN FINAL STATE");
            System.exit(-1);

        }


    }

}


class ClientEndState implements ClientState {

    @Override
    public void handleClientFsm() {

    }

    @Override
    public void communicateWithTheServer() {

    }

}
