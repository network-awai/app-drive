import Foundation
import Vision
let bytes = FileHandle.standardInput.readDataToEndOfFile()
let request = VNDetectBarcodesRequest()
request.symbologies = [.qr]
try VNImageRequestHandler(data: bytes).perform([request])
let results = request.results ?? []
guard results.count == 1, let value = results.first?.payloadStringValue else { exit(1) }
print(value)
