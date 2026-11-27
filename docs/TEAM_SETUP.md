# Setting up a team

How an organisation, a club or any other group of people who do not all know each other sets up a Zerion group, what the phones do when some of them are offline, and the limits that apply. This page is for the person who administers the team. The in-app version is the "Set up a team" help on the group settings screen. The wire format behind it is in [docs/wire/GROUPTR_WIRE_PROTOCOL.md](wire/GROUPTR_WIRE_PROTOCOL.md) (section "Who receives a post").

## The shape of a team

A Zerion group has no server. Every post travels from one phone to another over Tor, and a phone can send to another phone only when the two are contacts. Up to 3.0.18 this meant that every pair of members had to add each other. From 3.0.19 the phone of the Group owner passes posts on, so a team needs only one star of contacts: everyone is a contact of the owner, and the owner invites everyone.

1. One person creates the group. That person is its Group owner for the life of the group; ownership is not handed over.
2. The Group owner creates one permanent invite link: Settings, Invite links, Add invite link (the Add contact screen links there too). The link stays the same until the owner removes it, so it can go in an internal wiki, a mail or a printed note. The owner can have up to 5 such links, for example one per department.
3. Every team member opens Add contact, pastes the link, enters the name the owner should see and sends the request. The owner sees the requests on the Requests screen and accepts each one. From then on the member and the owner are contacts.
4. The Group owner opens the group, Group settings, Add member, and invites every member. A member who accepts is in the group.
5. Members who want to call each other, or want their posts to travel without the owner's phone, add each other as contacts. This is optional; the group works without it.

## What the owner's phone does

When a member posts, the member's phone sends the post directly to every member who is its own contact. For the members who are not, it hands the post to the owner's phone in one request that names those members. The owner's phone checks the author's signature and queues the unchanged post for each of them; it cannot alter or forge a post, it can only withhold one. The receiving phone checks the author's signature as if the post had come from the author. A post that arrives both directly and through the owner shows once.

The owner's own posts always travel directly, because the owner is a contact of every member it added.

This works between phones on 3.0.19 or later. A member on 3.0.18 or earlier, or a group whose owner runs 3.0.18 or earlier, stays out of reach of the members who are not its contacts, as before. The group screen shows a banner when members are out of reach; it names them, says whether they are reached through the owner's phone, and offers Add contact.

A post whose record is larger than one sync message (a post with a large attachment) travels directly only; the group screen says so.

## When the owner is offline

A post for a member the author cannot reach waits on the author's phone until the owner's phone is online, then moves to the owner's phone, where it waits in that member's queue until the member is online, for at most 7 days. Nothing is stored on any server at any point; the only copies are on the author's phone, the owner's phone and the receiving member's phone. A member who was offline for more than 7 days gets the posts that others still hold directly, and misses the passed-on copies that expired.

The owner's phone therefore shapes how quickly a large team sees each other's posts. A phone that is the owner of a large team should be online often: a dedicated phone that stays on a charger with the app open does this well. The phone needs nothing else; it keeps no extra data about the team beyond the group itself and its pairwise queues.

## Contact requests: limits and time windows

- A phone can have up to 5 permanent invite links. Each link accepts up to 200 requests that the owner has not yet decided on; further requests on that link are refused until the owner accepts or declines some. A ZerionID (the link on "Your ZerionID") accepts up to 50 undecided requests.
- A phone can have up to 20 open outgoing requests at a time (sent, not yet accepted or declined). If the owner sends the requests instead of receiving them, the owner's phone sends them in batches of 20; letting the members send to the owner avoids this.
- A request is retried for up to 30 days. The sender sees "No answer" after 32 days without a decision. A decline and a block are silent.
- Once the owner accepts, the requesting phone has 2 days to come online and finish the exchange. A requester who stays offline longer sends the request again.
- A ZerionID gets requests for 30 days after it was last shared, and it is replaced by a new one after each request accepted through it, so a ZerionID shared with a whole team stops working after the first acceptance. A permanent invite link is not replaced; it stays until its owner removes it. Share the permanent link with the team, not the ZerionID.
- Anyone who has the link can see when the owner's phone is online and can send requests. Remove the link when the team is complete if that matters.

## Size limits

- A group holds up to 256 members, including the owner.
- A phone keeps up to 200 contacts with a private connection (Tor client authorization, see [docs/protocol/ONION_CLIENT_AUTH.md](protocol/ONION_CLIENT_AUTH.md)). Contacts beyond that still work: they use the phone's open onion address, and the contact's connection details say "This device cannot hold more than 200 contacts with a private connection." When a contact is removed, the next contact waiting for a private connection gets it.
- Member invites (Group settings, "Members can invite their contacts") let a member bring in its own contacts. Such an invitee is a member but not necessarily the owner's contact, so the owner's phone cannot pass posts on to or from them until they add the owner. For a team, keep member invites off and let every member add the owner.

## Checklist for the administrator

1. Create the group on the phone that will stay online.
2. Create a permanent invite link and share it with the team.
3. Accept the requests as they come in; the Requests screen lists them.
4. Invite every accepted contact to the group.
5. Tell members they may add each other for calls and direct delivery, and that posts to members they have not added arrive once the owner's phone has passed them on.
6. Keep the owner's phone online as much as possible.
