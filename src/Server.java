import java.awt.BorderLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Scanner;

import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.filechooser.FileSystemView;


/**
 * Creates a instance of server that receives requests from client/intermediate host and responds
 * 
 * Team 11  
 * V1.16
 */
public class Server {
	private DatagramSocket receiveSocket;
	private ArrayList<Thread> threads;
	private File directory;
	private boolean verbose;

	//Well-known server port number
	private static final int PORT_NUMBER = 69;

	/*
	 *   Creates new thread, receiveSocket, and sendSocket
	 */
	public Server()
	{
		directory = null;
		threads = new ArrayList<Thread>();
		try {
			receiveSocket = new DatagramSocket(PORT_NUMBER);
		} catch (SocketException e) {
			e.printStackTrace();
		}
	}

	/*
	 *   Waits for request then processes that request  
	 */
	public synchronized void sendReceive()
	{
		while(true)
		{
			//Waits to receive DatagramPacket from intermediate host
			System.out.println("Server waiting...");
			byte[] msg = new byte[100];
			DatagramPacket receivedPacket = new DatagramPacket(msg, msg.length);
			try {
				receiveSocket.receive(receivedPacket);
			} catch (IOException e) {
				e.printStackTrace();
				System.exit(1);
			}
			System.out.println("Request received from Host: " + Converter.convertMessage(msg));
			
			addThread(new ControlThread(receivedPacket));
		}
	}

	/*
	 * Enables the user to select which directory will act as the server's file system
	 */
	private File getDirectory()
	{
		JFileChooser directoryFinder = new JFileChooser();
		directoryFinder.setDialogTitle("Server Directory");
		directoryFinder.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
		directoryFinder.setAcceptAllFileFilterUsed(false);
		if (directoryFinder.showOpenDialog(directoryFinder) == JFileChooser.APPROVE_OPTION) { 
			return directoryFinder.getSelectedFile();
		}
		else
		{
			return null;
		}
	}

	/*
	 *   Adds a new thread
	 */
	private void addThread(Thread t)
	{
		threads.add(t);
		t.start();
	}
	
	private void removeThread(Thread t)
	{
		threads.remove(t);
	}
	
	private class ControlThread extends Thread
	{
		private DatagramPacket packet;
		
		public ControlThread(DatagramPacket packet)
		{
			this.packet = packet;
		}
		
		@Override
		public void run()
		{
			byte[] msg = packet.getData();
			//Checks if request is valid (read or write)
			if(!(msg[0] == 0 && (msg[1] == 1 || msg[1] == 2)))
			{
				System.out.println("Request is invalid.");
				System.exit(1);
			}

			int zeroCount = 0;
			int count = 0;
			for(int i = 2; i < msg.length; i++)
			{
				if(zeroCount < 2)
				{
					if(msg[i] == 0 && count == 0)
					{
						System.out.println("Request is invalid.");
						System.exit(1);
					}
					else if(msg[i] == 0)
					{
						count = 0;
						zeroCount++;
					}
					else
					{
						count++;
					}
				}
				else
				{
					if(msg[i] != 0)
					{
						System.out.println("Request is invalid.");
						System.exit(1);
					}
				}
			}

			//Extracts the filename
			int index = -1;
			for(int i = 2; i < msg.length; i++)
			{
				if(msg[i] == 0)
				{
					index = i;
					i = msg.length;
				}
			}
			byte[] b = new byte[index - 2];
			int j = 2;
			for(int i = 0; i < b.length; i++, j++)
			{
				b[i] = msg[j];
			}
			//Turns filename that is a byte array into a string
			String filename = new String(b);
			//Build "file" object from the specified filepath
			File f = new File(directory + "\\" + filename);
			Path path = Paths.get(directory + "\\" + filename);
			
			//If Else determine Read request or Write request
			//Creates new read thread with filename
			if(msg[1] == 1)
			{
				//Check if file exists
				if(!f.exists())
				{
					System.out.println("Failed to read: 0501 - File not found. " + filename);
					System.out.println("Sending error packet . . .");
					createSendError(new Byte("1"), packet, receiveSocket);
				}
				//Check if the file can be read
				else if(!Files.isReadable(path)){
					System.out.println("Failed to read: 0502 - Access Violation. " + filename);
					createSendError(new Byte("2"), packet, receiveSocket);
				}
				//No errors, send valid response
				else{
					System.out.println("The request is a valid read request.");
					addThread(new ReadThread(packet.getPort(), filename));
				}
			}
			//Creates new write thread with filename
			else
			{
				//Check if the file already exists
				 if(Files.exists(path)){
					System.out.println("Failed to write: 0506 - File already exists " + filename);
					System.out.println("Sending error packet . . .");
					createSendError(new Byte("6"), packet, receiveSocket);
				}
				//Check if can write
				 else if(Files.isWritable(path)){
					System.out.println("Failed to read: 0502 - Access Violation. " + filename);
					System.out.println("Sending error packet . . .");
					createSendError(new Byte("2"), packet, receiveSocket);
				}
				//Check if there is enough space on the server
				else if(f.getParentFile().getFreeSpace() < packet.getData().length){
					System.out.println("Failed to write: 0503 - Not enough disk space. " + filename);
					System.out.println("Sending error packet . . .");
					createSendError(new Byte("3"), packet, receiveSocket);
				}

				else{
					System.out.println("The request is a valid write request.");
					addThread(new WriteThread(packet.getPort(), filename));
				}
			}
			//Close and Erase this instance of the control thread as it is no longer useful
			removeThread(this);
		}

		private void createSendError(byte errorNum, DatagramPacket receivePacket, DatagramSocket socket){
			byte[] b = {0, 5, 0, errorNum};
			try {			
				DatagramPacket ack = new DatagramPacket(b, b.length, InetAddress.getLocalHost(), receivePacket.getPort());
				try {
					socket.send(ack);
				}catch (IOException IOE){
					IOE.printStackTrace();
					System.exit(1);
				}
			} catch (UnknownHostException e){
				e.printStackTrace();
				System.exit(1);
			}
		}
	}

	/*
	 *   Creates a thread that will process a read request
	 *   -- data being read from Server and sent to Client
	 */
	private class ReadThread extends Thread
	{
		private DatagramSocket socket;
		private int hostPort;
		private String filename;
		
		/*
		 *   Creates new socket and initiates filename and port
		 */
		public ReadThread(int hostPort, String filename)
		{
			this.hostPort = hostPort;
			this.filename = filename;
			try {
				socket = new DatagramSocket();
			} catch (SocketException e) {
				e.printStackTrace();
			}
		}

		/*
		 *   Sends data back to the client and waits for an ack  
		 */
		private synchronized void sendReceive()
		{
			//Reads from the file	
			byte[] data = new byte[512];
			FileInputStream is = null;
			try {
				System.out.println(filename);
				System.out.println(directory.getAbsolutePath() + "\\" + filename);
				is = new FileInputStream(directory.getAbsolutePath() + "\\" + filename);
			} catch (FileNotFoundException e2) {
				e2.printStackTrace();
			}
			byte[] receiveMsg = new byte[4];
			int available = 0;
			
			//Error Verification Variables 
			int dataBlockCounter = -1;
			int ACKcounter=-1; 
			int tempIncomingACK=0;
			boolean ACKdelay = false; 
			boolean ACKlost = false;
			try {
				is.read(data);
			} catch (IOException e1) {
				e1.printStackTrace();
			}
			DatagramPacket msg = buildData(data, ++dataBlockCounter, hostPort);
			try {
				socket.send(msg);
				System.out.println("DATA packet sent : data packet #"+(dataBlockCounter));
			} catch (IOException e) {
				e.printStackTrace();
			}
			try {
				available=is.available();
			} catch (IOException e2) {
				e2.printStackTrace();
			}
			//Continually reads data from the file until no more data is available
			while(available > 0) {
				ACKdelay = false;
				ACKlost = false;
				receiveMsg = new byte[4];

				DatagramPacket receivePacket = new DatagramPacket(receiveMsg, receiveMsg.length);
				try {
					socket.setSoTimeout(2000);
					socket.receive(receivePacket);
				} catch (SocketTimeoutException ste){
					System.out.println("Ack is delayed for block number "+(dataBlockCounter));
					ACKdelay = true;
				} catch (IOException e) { e.printStackTrace();}
				if (ACKdelay){
					try {
						socket.setSoTimeout(4000);
						socket.receive(receivePacket);
					} catch (SocketTimeoutException ste){
						System.out.println("Ack is considdered lost.. Resending packet");
						ACKdelay = false;
						ACKlost = true;
					} catch (IOException e) {e.printStackTrace();}
				}
				if (ACKlost){//re-send packet
					try{ 
						socket.send(msg);
						ACKlost=false;
					} catch(IOException e){	e.printStackTrace();}
				} else {
					System.out.println("Response received from Client: " + Arrays.toString(receiveMsg) + "\n");
					if (receiveMsg[0]!=0 || receiveMsg[1]!=4) {
						System.out.println("Strange error.. exiting");
						System.exit(1);
					}
					//parse two bytes into int.
					tempIncomingACK = ((receiveMsg[2] & 0xFF)<<8) | (receiveMsg[3] & 0xFF);
					
					//duplicate/delayed ACK packet restart loop:
					if (tempIncomingACK <= ACKcounter){
						//msg should contain previous DatagramPacket to send
						System.out.println("Received old ACK statent, delay and/or duplication Error detected\nResending Packet");
						try{ 
							socket.send(msg);
						} catch(IOException e){
							e.printStackTrace();
						}
					} //expected result ACK corresponds to last block sent
					else if(tempIncomingACK == dataBlockCounter){
						ACKcounter = tempIncomingACK;
						data = new byte[512];
						try {
							is.read(data);
						} catch (IOException e1) {
							e1.printStackTrace();
						}
						msg = buildData(data, ++dataBlockCounter, hostPort);
						try {
							System.out.println("DATA packet sent : data packet #"+(dataBlockCounter));
							socket.send(msg);
						} catch (IOException e) {
							e.printStackTrace();
						}
						try {
							available = is.available();
						} catch (IOException e1) {
							e1.printStackTrace();
						}
						
						System.out.println("Bytes left in file"+available);
					}//default case for circumstance where tempIncomingACK > ACKcounter which should never be possible.
					else{
						System.out.println("Unexpected Error Occurred, ACK for future packet Recieved");
						try{ 
							socket.send(msg);
						} catch(IOException e){
							e.printStackTrace();
						}
					}
				}		
			}//END Loop
			//Receive Final ACK to make sure that the thing sent:
			while (ACKcounter < dataBlockCounter){
				System.out.println("entered seccond loop because ACKcounter is "+ACKcounter + " while block number is: "+dataBlockCounter);
				
				ACKdelay=false; ACKlost=false;
				
				receiveMsg = new byte[4];
				DatagramPacket receivePacket = new DatagramPacket(receiveMsg, receiveMsg.length);
				try {
					socket.setSoTimeout(2000);
					socket.receive(receivePacket);
				} catch (SocketTimeoutException ste){
					System.out.println("Ack is delayed for block number "+(dataBlockCounter));
					ACKdelay = true;
				} catch (IOException e) {
					e.printStackTrace();
				}
				if (ACKdelay){
					try {
						socket.setSoTimeout(4000);
						socket.receive(receivePacket);
					} catch (SocketTimeoutException ste){
						System.out.println("Ack is considdered lost.. Resending packet");
						ACKdelay = false;
						ACKlost = true;
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
				if (ACKlost){//re-send packet
					try{ 
						socket.send(msg);
						ACKlost=false;
					} catch(IOException e){
						e.printStackTrace();
					}
				}else {
					if (receiveMsg[0]!=0 || receiveMsg[1]!=4) {
						System.out.println("Strange error.. exiting");
						System.exit(1);
					}
					tempIncomingACK = ((receiveMsg[2] & 0xFF)<<8) | (receiveMsg[3] & 0xFF);
					if(tempIncomingACK == dataBlockCounter) {
						ACKcounter = tempIncomingACK;
						System.out.println("ACK recieved ");
						System.out.println("Response received from Client: " + Arrays.toString(receiveMsg) + "\n");
						break;
					}else {
						try{ socket.send(msg);} catch(IOException e){ e.printStackTrace();}
					}
				}
			}//END loop 
			try {
				is.close();
			} catch (IOException e) {
				e.printStackTrace();
			}
		}

		/*
		 *    Builds a DatagramPacket containing the data
		 */
<<<<<<< HEAD
		private synchronized DatagramPacket buildData(byte[] data, int blockNumber, int portNumber)
=======
		private synchronized DatagramPacket buildData(byte[] data, int dataBlockCounter, int portNumber)
>>>>>>> refs/remotes/origin/master
		{
			//Adds the code for data block(03) followed by block number
			byte[] msg = new byte[516];
			msg[1] = 3;
<<<<<<< HEAD
			msg[2] = (byte)((blockNumber >> 8) & 0xFF);
			msg[3] = (byte)(blockNumber & 0xFF);
=======
			msg[2] = (byte) ((byte)dataBlockCounter/256);
			msg[3] = (byte) ((byte)dataBlockCounter - dataBlockCounter/256);
>>>>>>> refs/remotes/origin/master

			//Adds the data to the byte array
			for(int j = 0, k = 4; j < data.length && k < msg.length; j++, k++)
			{
				msg[k] = data[j];
			}

			//Creates DatagramPacket and returns it
			DatagramPacket send = null;
			try {
				send = new DatagramPacket(msg, msg.length, InetAddress.getLocalHost(), portNumber);
			} catch (UnknownHostException e) {
				e.printStackTrace();
			}
			return send;
		}

		@Override
		public void run()
		{
			sendReceive();
			removeThread(this);
		}
	}

	/*
	 *   Creates a thread that will process a write request
	 */
	private class WriteThread extends Thread
	{
		private DatagramSocket socket;
		private int hostPort;
		private String filename;

		/*
		 *   Creates new socket and initiates filename and port
		 */
		public WriteThread(int hostPort, String filename)
		{
			this.hostPort = hostPort;
			this.filename = filename;
			try {
				socket = new DatagramSocket();
			} catch (SocketException e) {
				e.printStackTrace();
			}
		}
		
		public String getFilename()
		{
			return filename;
		}

		/*
		 *   Sends request to intermediate host then writes to the file  
		 */
		private synchronized void sendReceive()
<<<<<<< HEAD
		{	
=======
		{
>>>>>>> refs/remotes/origin/master
			//sends first ACK to confirm that the Client can continue the file transfer. 
			byte[] b = {0, 4, 0, 0};
			DatagramPacket ack = null;
			try {
				ack = new DatagramPacket(b, b.length, InetAddress.getLocalHost(), hostPort);
			} catch (UnknownHostException e2) {	e2.printStackTrace();}
			try {
				socket.send(ack);
			} catch (IOException e2) { e2.printStackTrace();}
			//Sets up Error Detection Variables
			int blockNum=0;
			int incomingBlockID=0;
			boolean delayed = false; 
			boolean lost = false; 
			//Sets up fileOuputStream
			int index = -1;
			byte[] receiveMsg;
			FileOutputStream fos = null;
			try {
				fos = new FileOutputStream(new File(directory.getAbsolutePath() + "\\" + filename));
			} catch (FileNotFoundException e1) {
				e1.printStackTrace();
			}

			//Continually write to the file
			while(index == -1) {
				
				//resets the packet duplicate checker;
				
				//Receives data from intermediate host
				receiveMsg = new byte[516];
				DatagramPacket receivePacket = new DatagramPacket(receiveMsg, receiveMsg.length);
				try {
					socket.setSoTimeout(2000);
					System.out.println("Waiting for data. . .");
					socket.receive(receivePacket);
				}catch (SocketTimeoutException ste){
					delayed=true; 
					System.out.println("Data Delayed, waiting...");
				} catch (IOException e) {e.printStackTrace();}
				
				if(delayed){
					try {
						socket.setSoTimeout(5000);
						socket.receive(receivePacket);
					} catch (SocketTimeoutException ste) {
						lost = true;
						delayed= false;
						System.out.println("Packet declared LOST, Resending Packet");
					} catch (IOException e) {e.printStackTrace();}
				}
				
				if (lost){
					try{
						socket.send(ack);
						lost=false; 
					} catch (IOException e){
						e.printStackTrace();
					}
				} else { //run normally check for duplicates
					incomingBlockID = ((receivePacket.getData()[2]&0xFF)<<8) | (receivePacket.getData()[3] & 0xFF);
					System.out.println("block id incoming:"+incomingBlockID+" and blockNUM: "+(blockNum+1));
					if(incomingBlockID == blockNum+1){
						blockNum=incomingBlockID;
						System.out.println("recieved Block Num "+blockNum);
						//Copies the data into a new array
						byte[] data = new byte[512];
						for(int i = 0, j = 4; i < data.length && j < receiveMsg.length; i++, j++)
						{
							data[i] = receiveMsg[j];
						}
						//TODO: combine for loops, why can't if statement follow statement in first for
						for(int k = 0; k < data.length; k++) {
							if(data[k] == 0){
								index = k;
								k = data.length;
							}
						}
						//Creates and sends acknowledgement to the intermediate host
						b[2]=(byte)((blockNum >> 8)& 0xFF);//moves all bits 8 to the right then masks all but the right most 8 bits, 
						b[3]=(byte)(blockNum & 0xFF); // masks all but the right most 8 bits.
						try {
							ack = new DatagramPacket(b, b.length, InetAddress.getLocalHost(), hostPort);
						} catch (UnknownHostException e2) {
							e2.printStackTrace();
						}
						try {
							socket.send(ack);
						}catch (IOException IOE){
							IOE.printStackTrace();
							System.exit(1);
						}
						
						//Writes data to the file
						if(index == -1){
							try {
								fos.write(data);
							} catch (IOException e) {
								e.printStackTrace();
							}

							//Writes the last bit of data to the file
						} else{
							System.out.println("Entered final loop, index is not -1 anymore.");
							try {
								fos.write(data, 0, index);
							} catch (IOException e) {
			    					e.printStackTrace();
			    				}
							try {
								fos.close();
							} catch (IOException e) {
								e.printStackTrace();
							}
							//TODO: put into method helper to clean code. 
							//checks to see if the Client has any messages or had any issues with final ACK.
							boolean received=false; 
							do{
								received = true;
								try {
									socket.setSoTimeout(10000);
									socket.receive(receivePacket);
								}catch (SocketTimeoutException ste){
									received = false;
									System.out.println("Assumed that the Client Received the Final ACK after 10 secconds without a message");
								} catch(IOException e){e.printStackTrace();}
								if (received){
									incomingBlockID = ((receiveMsg[2] & 0xFF)<<8) | (receiveMsg[3] & 0xFF);
									if(incomingBlockID <= blockNum){
										try{ socket.send(ack);} catch(IOException e) {e.printStackTrace();}
										System.out.println("Resending ACK");
									}
								}
							}while(received);
						}//end of running properly, exits while. 	
					}else if (incomingBlockID <= blockNum){
						System.out.println("incoming block is a duplicate");
						
					} else {
						System.out.println("Unexpected Error Occured, Recieved Future data Packet");
						try {
							socket.send(ack);
						} catch(IOException e){
							e.printStackTrace();
						}
					}
				} 
			}//END While
		}
		@Override
		public void run()
		{
			sendReceive();
			removeThread(this);
		}
	}
	/*
	 *    Creates a server instance and runs it
	 */
	public static void main(String args[])
	{
		Server server = new Server();
		server.setUp();
	}
	
	public void setUp()
	{
		new ServerSetup();
	}
	
	@SuppressWarnings("serial")
	private class ServerSetup extends JDialog
	{
		private File file;
		private JRadioButton verboseRadio;
		private JTextField directoryPath;
		private JButton browseButton;
		private JButton okButton;
		private JButton cancelButton;
		
		public ServerSetup()
		{
			this.file = FileSystemView.getFileSystemView().getHomeDirectory();
			this.directoryPath = new JTextField(file.getAbsolutePath());
			this.directoryPath.setColumns(25);
			this.browseButton = new JButton("Browse...");
			this.okButton = new JButton("OK");
			this.cancelButton = new JButton("Cancel");
			
			this.verboseRadio = new JRadioButton("Verbose", true);
			JRadioButton quietRadio = new JRadioButton("Quiet");
			
			ButtonGroup g = new ButtonGroup();
			g.add(verboseRadio);
			g.add(quietRadio);
			
			JPanel directoryPanel = new JPanel();
			directoryPanel.add(new JLabel("Client Directory: "));
			directoryPanel.add(this.directoryPath);
			directoryPanel.add(this.browseButton);
			
			JPanel outputPanel = new JPanel();
			outputPanel.add(this.verboseRadio);
			outputPanel.add(quietRadio);
			
			JPanel buttonPanel = new JPanel();
			buttonPanel.add(this.okButton);
			buttonPanel.add(this.cancelButton);

			this.add(directoryPanel, BorderLayout.NORTH);
			this.add(outputPanel, BorderLayout.CENTER);
			this.add(buttonPanel, BorderLayout.SOUTH);
			this.pack();
			this.setUpListeners();
			this.setLocationRelativeTo(null);
			this.setTitle("Client Setup");
			this.setVisible(true);
			this.setDefaultCloseOperation(DISPOSE_ON_CLOSE);
		}
		
		private void setUpListeners()
		{
			okButton.addActionListener(new ActionListener()
			{
				@Override
				public void actionPerformed(ActionEvent e) 
				{
					directory = file;
					if(verboseRadio.isSelected()) verbose = true;
					else verbose = false;
					dispose();
					sendReceive();
				}
			});
			cancelButton.addActionListener(new ActionListener()
			{
				@Override
				public void actionPerformed(ActionEvent e) 
				{
					System.out.println("Server creation cancelled.\n");
					dispose();
					System.exit(0);
				}
			});
			browseButton.addActionListener(new ActionListener()
			{
				@Override
				public void actionPerformed(ActionEvent e) 
				{
					File tempFile = getDirectory();
					if(tempFile != null)
					{
						file = tempFile;
						directoryPath.setText(tempFile.getAbsolutePath());
					}
				}
			});
		}
		
	}

}
