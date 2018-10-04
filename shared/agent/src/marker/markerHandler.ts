"use strict";
import * as path from "path";
import { Range } from "vscode-languageserver";
import URI from "vscode-uri";
import {
	DocumentFromCodeBlockRequest,
	DocumentFromCodeBlockResponse,
	DocumentMarkersRequest,
	DocumentMarkersResponse,
	MarkerWithRange
} from "../agent";
import { Container } from "../container";
import { Logger } from "../logger";
import { MarkerLocationManager } from "../markerLocation/markerLocationManager";

export namespace MarkerHandler {
	const emptyResponse = {
		markers: []
	};

	// export function onHover(e: TextDocumentPositionParams) {
	// 	Logger.log("Hover request received");
	// 	return undefined;
	// }

	export async function documentMarkers({
		textDocument: documentId
	}: DocumentMarkersRequest): Promise<DocumentMarkersResponse> {
		try {
			const { streamManager, markerManager } = Container.instance();
			const filePath = URI.parse(documentId.uri).fsPath;
			Logger.log(`MARKERS: requested markers for ${filePath}`);
			const stream = await streamManager.getByPath(filePath);
			if (!stream) {
				Logger.log(`MARKERS: no streamId found for ${filePath} - returning empty response`);
				return emptyResponse;
			}

			const markersById = await markerManager.getByStreamId(stream.id, true);
			const markers = Array.from(markersById.values());
			Logger.log(`MARKERS: found ${markers.length} markers - retrieving current locations`);
			const locations = await MarkerLocationManager.getCurrentLocations(documentId.uri);
			for (const mrk of markers) {
				const loc = locations[mrk.id] || {};
				Logger.log(
					`MARKERS: ${mrk.id}=[${loc.lineStart}, ${loc.colStart}, ${loc.lineEnd}, ${loc.colEnd}]`
				);
			}
			const markersWithRange = markers.map(
				m =>
					({
						...m,
						range: MarkerLocationManager.locationToRange(locations[m.id])
					} as MarkerWithRange)
			);

			return {
				markers: markersWithRange
			};
		} catch (err) {
			console.error(err);
			debugger;
			return emptyResponse;
		}
	}

	export async function documentFromCodeBlock({
		repoId,
		file,
		markerId
	}: DocumentFromCodeBlockRequest): Promise<DocumentFromCodeBlockResponse | undefined> {
		const { git } = Container.instance();

		const repo = await git.getRepositoryById(repoId);
		if (repo === undefined) return undefined;

		const filePath = path.join(repo.path, file);
		const documentUri = URI.file(filePath).toString();

		const locationsById = await MarkerLocationManager.getCurrentLocations(documentUri);
		const location = locationsById[markerId];
		const range = location
			? MarkerLocationManager.locationToRange(location)
			: Range.create(0, 0, 0, 0);

		return {
			textDocument: { uri: documentUri },
			range: range,
			revision: undefined
		};
	}
}
