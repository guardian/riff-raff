package magenta.deployment_type

import com.amazonaws.services.ec2.model.{Filter, DescribeImagesRequest}
import magenta.KeyRing
import magenta.tasks.EC2
import scala.collection.JavaConverters._

trait UpToDateImage extends EC2 {
  def latestImage(searchTags: Map[String, String])(implicit keyRing: KeyRing): Option[String] = {
    val filters = searchTags.map { case (key, value) =>
        new Filter(s"tag:$key").withValues(value)
    }

    val request = new DescribeImagesRequest()
    request.setOwners(List("self").asJava)
    request.setFilters(filters.asJavaCollection)
    val allImages = client.describeImages(request).getImages.asScala
    val sortedImages = allImages.sortBy(_.getCreationDate)
    sortedImages.lastOption.map(_.getImageId)
  }
}
