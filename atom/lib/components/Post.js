import React, { Component } from "react";
import Gravatar from "react-gravatar";
import Timestamp from "./Timestamp";
import Menu from "./Menu";
import createClassString from "classnames";

export default class Post extends Component {
	constructor(props) {
		super(props);
		this.state = {
			post: props.post,
			menuOpen: false
		};
	}

	componentDidMount() {
		// FIXME -- probably don't want to be doing something to parent here
		let streamDiv = this._div.parentNode.parentNode;
		let currentScroll = streamDiv.scrollTop;
		let scrollHeight = streamDiv.scrollHeight;
		let offBottom = scrollHeight - currentScroll - streamDiv.offsetHeight - this._div.offsetHeight;
		// if i am manually scrolling, don't programatically scroll to bottom
		// unless the post is mine, in which case we always scroll to bottom
		// we check to see if it's below 100 because if you are scrolled
		// almost to the bottom, we count that as being at the bottom for UX reasons
		if (offBottom < 100 || this.state.post.nick == "pez") {
			// big number to make sure we've scrolled all the way down
			streamDiv.scrollTop = 100000;
		}

		if (this.props.post.fullName)
			atom.tooltips.add(this._authorDiv, { title: this.props.post.fullName });
	}

	render() {
		const { post } = this.state;

		const postClass = createClassString({
			post: true,
			"new-separator": post.newSeparator
		});
		console.log(postClass);

		const codeblock = post.quoteText ? <div className="code">{post.quoteText}</div> : "";

		// FIXME -- only replace the at-mentions of actual authors, rather than any
		// string that starts with an @
		let body = post.body.replace(/(@\w+)/g, <span class="at-mention">$1</span>);
		let bodyParts = post.body.split(/(@\w+)/);

		let menuItems = [
			{ label: "Mark Unread", key: "mark-unread" },
			{ label: "Add Reaction", key: "add-reaction" },
			{ label: "Pin to Stream", key: "pin-to-stream" },
			{ label: "Edit Message", key: "edit-message" },
			{ label: "Delete Message", key: "delete-message" }
		];

		let menu = this.state.menuOpen ? <Menu items={menuItems} /> : null;

		// FIXME use a real email address
		return (
			<div
				className={postClass}
				id={post.id}
				onClick={this.handleClick}
				ref={ref => (this._div = ref)}
			>
				<span className="icon icon-gear" onClick={this.handleMenuClick} />
				{menu}
				<Gravatar
					className="headshot"
					size={36}
					default="retro"
					protocol="http://"
					email={post.email}
				/>
				<author ref={ref => (this._authorDiv = ref)}>{post.nick}</author>
				<Timestamp time={post.timestamp} />
				<div className="body">
					{bodyParts.map(part => {
						if (part.charAt(0) == "@") {
							return <span class="at-mention">{part}</span>;
						} else {
							return part;
						}
					})}
					{codeblock}
				</div>
			</div>
		);
	}

	handleClick = async event => {
		console.log("CLICK ON POST: " + event.target.innerHTML);
	};

	handleMenuClick = async event => {
		event.stopPropagation();
		this.setState({ menuOpen: !this.state.menuOpen });
		console.log("CLICK ON MENU: ");
	};
}
