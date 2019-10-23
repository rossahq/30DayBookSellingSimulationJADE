package set10111.simulation;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.Predicate;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.Behaviour;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.OneShotBehaviour;
import jade.core.behaviours.SequentialBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAException;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

public class BuyerAgent extends Agent {
	private ArrayList<AID> sellers = new ArrayList<>();
	private ArrayList<String> booksToBuy = new ArrayList<>();
	private HashMap<String, ArrayList<Offer>> currentOffers = new HashMap<>();
	private AID tickerAgent;
	private int numQueriesSent;
	private int totalExpenditure;

	@Override
	protected void setup() {
		//add this agent to the yellow pages
		DFAgentDescription dfd = new DFAgentDescription();
		dfd.setName(getAID());
		ServiceDescription sd = new ServiceDescription();
		sd.setType("buyer");
		sd.setName(getLocalName() + "-buyer-agent");
		dfd.addServices(sd);
		try {
			DFService.register(this, dfd);
		} catch (FIPAException e) {
			e.printStackTrace();
		}
		//add books to buy
		booksToBuy.add("Java for Dummies");
		booksToBuy.add("JADE: the Inside Story");
		booksToBuy.add("Multi-Agent Systems for Everybody");

		addBehaviour(new TickerWaiter(this));
	}


	@Override
	protected void takeDown() {
		//Deregister from the yellow pages
		try {
			DFService.deregister(this);
		} catch (FIPAException e) {
			e.printStackTrace();
		}
	}

	public class TickerWaiter extends CyclicBehaviour {

		//behaviour to wait for a new day
		public TickerWaiter(Agent a) {
			super(a);
		}

		@Override
		public void action() {
			MessageTemplate mt = MessageTemplate.or(MessageTemplate.MatchContent("new day"),
					MessageTemplate.MatchContent("terminate"));
			ACLMessage msg = myAgent.receive(mt);
			if (msg != null) {
				if (tickerAgent == null) {
					tickerAgent = msg.getSender();
				}
				if (msg.getContent().equals("new day")) {
					//spawn new sequential behaviour for day's activities
					SequentialBehaviour dailyActivity = new SequentialBehaviour();
					//sub-behaviours will execute in the order they are added
					dailyActivity.addSubBehaviour(new FindSellers(myAgent));
					dailyActivity.addSubBehaviour(new SendEnquiries(myAgent));
					dailyActivity.addSubBehaviour(new CollectOffers(myAgent));
					dailyActivity.addSubBehaviour(new SendPurchaseOrder(myAgent));
					dailyActivity.addSubBehaviour(new EndDay(myAgent));
					myAgent.addBehaviour(dailyActivity);
				} else {
					//termination message to end simulation
					System.out.println("Total expenditure on books: " + totalExpenditure);
					myAgent.doDelete();
				}
			} else {
				block();
			}
		}

	}

	public class FindSellers extends OneShotBehaviour {

		public FindSellers(Agent a) {
			super(a);
		}

		@Override
		public void action() {
			DFAgentDescription sellerTemplate = new DFAgentDescription();
			ServiceDescription sd = new ServiceDescription();
			sd.setType("seller");
			sellerTemplate.addServices(sd);
			try {
				sellers.clear();
				DFAgentDescription[] agentsType1 = DFService.search(myAgent, sellerTemplate);
				for (int i = 0; i < agentsType1.length; i++) {
					sellers.add(agentsType1[i].getName()); // this is the AID
				}
			} catch (FIPAException e) {
				e.printStackTrace();
			}

		}

	}

	public class SendEnquiries extends OneShotBehaviour {

		public SendEnquiries(Agent a) {
			super(a);
		}

		@Override
		public void action() {
			//send out a call for proposals for each book
			numQueriesSent = 0;
			for (String bookTitle : booksToBuy) {
				ACLMessage enquiry = new ACLMessage(ACLMessage.CFP);
				enquiry.setContent(bookTitle);
				enquiry.setConversationId(bookTitle);
				for (AID seller : sellers) {
					enquiry.addReceiver(seller);
					numQueriesSent++;
				}
				myAgent.send(enquiry);

			}

		}
	}

	public class CollectOffers extends Behaviour {
		private int numRepliesReceived = 0;

		public CollectOffers(Agent a) {
			super(a);
			currentOffers.clear();
		}


		@Override
		public void action() {
			boolean received = false;
			for (String bookTitle : booksToBuy) {
				MessageTemplate mt = MessageTemplate.MatchConversationId(bookTitle);
				ACLMessage msg = myAgent.receive(mt);
				if (msg != null) {
					received = true;
					numRepliesReceived++;
					if (msg.getPerformative() == ACLMessage.PROPOSE) {
						//we have an offer
						//the first offer for a book today
						if (!currentOffers.containsKey(bookTitle)) {
							ArrayList<Offer> offers = new ArrayList<>();
							offers.add(new Offer(msg.getSender(),
									Integer.parseInt(msg.getContent())));
							currentOffers.put(bookTitle, offers);
						}
						//subsequent offers
						else {
							ArrayList<Offer> offers = currentOffers.get(bookTitle);
							offers.add(new Offer(msg.getSender(),
									Integer.parseInt(msg.getContent())));
						}

					}

				}
			}
			if (!received) {
				block();
			}
		}


		@Override
		public boolean done() {
			return numRepliesReceived == numQueriesSent;
		}

		@Override
		public int onEnd() {
			//print the offers
			for (String book : booksToBuy) {
				if (currentOffers.containsKey(book)) {
					ArrayList<Offer> offers = currentOffers.get(book);
					for (Offer o : offers) {
						System.out.println(book + "," + o.getSeller().getLocalName() + "," + o.getPrice());
					}
				} else {
					System.out.println("No offers for " + book);
				}
			}
			return 0;
		}

	}


	public class EndDay extends OneShotBehaviour {

		public EndDay(Agent a) {
			super(a);
		}

		@Override
		public void action() {
			ACLMessage msg = new ACLMessage(ACLMessage.INFORM);
			msg.addReceiver(tickerAgent);
			msg.setContent("done");
			myAgent.send(msg);
			//send a message to each seller that we have finished
			ACLMessage sellerDone = new ACLMessage(ACLMessage.INFORM);
			sellerDone.setContent("done");
			for (AID seller : sellers) {
				sellerDone.addReceiver(seller);
			}
			myAgent.send(sellerDone);
		}

	}

	public class SendPurchaseOrder extends OneShotBehaviour {


		public SendPurchaseOrder(Agent a) {
			super(a);
		}

		@Override
		public void action() {
			ACLMessage rejectOffer = new ACLMessage((ACLMessage.REJECT_PROPOSAL));
			ACLMessage purchaseOrder = new ACLMessage((ACLMessage.ACCEPT_PROPOSAL));
			purchaseOrder.setContent("buy");
			for (String book : booksToBuy) {
				if (currentOffers.containsKey(book)) {
					ArrayList<Offer> offers = currentOffers.get(book);

					ArrayList<AID> sellers = new ArrayList<>();
					for (Offer o : offers) {
						sellers.add(o.getSeller());
					}

					Offer bestOffer = new Offer(new AID("1"), 0);
					for (Offer offer : offers) {
						if (offer.getPrice() < bestOffer.getPrice()) {
							bestOffer = offer;
						}
						purchaseOrder.setConversationId(book);
						purchaseOrder.addReceiver(bestOffer.getSeller());
						myAgent.send(purchaseOrder);

						sellers.remove(bestOffer.getSeller());
						rejectOffer.setContent("refuse");
						rejectOffer.setConversationId(book);

						for (AID seller : sellers) {
							rejectOffer.addReceiver(seller);
						}
						myAgent.send(rejectOffer);
					}
				}
				doWait(2000);
				MessageTemplate mt = MessageTemplate.MatchConversationId(book);
				ACLMessage msg = myAgent.receive(mt);
				if (msg != null) {
					if (msg.getPerformative() == ACLMessage.INFORM) {
						booksToBuy.remove(book);
						System.out.println("Successfully purchased: " + book + " for £" + msg.getContent());
						totalExpenditure = totalExpenditure + Integer.parseInt(msg.getContent());

					}
				}
			}
		}
	}
}




